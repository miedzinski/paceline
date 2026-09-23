#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
install_directory="/opt/paceline"
configuration_directory="/etc/paceline"
example_configuration="$script_directory/application.yml.example"
example_secrets="$script_directory/paceline.env.example"
build_completed="${PACELINE_INSTALL_BUILD_COMPLETED:-0}"

usage() {
    echo "Usage: $0 [--uninstall]"
    echo "Without options, builds and installs the GraalVM native executable."
    echo "--uninstall stops Paceline and removes its service, files, account, and group."
}

build_application() (
    if ! command -v docker >/dev/null 2>&1; then
        echo "Docker is required to build Paceline's native executable." >&2
        exit 1
    fi

    if ! docker info >/dev/null 2>&1; then
        echo "Docker is installed but its daemon is unavailable to this user." >&2
        exit 1
    fi

    local output_directory="$project_directory/build/native/nativeCompile"
    local image_tag="paceline-native-build:installer-$$"
    local container_id=""
    local staging_directory=""

    cleanup_docker_build() {
        if [[ -n "$container_id" ]]; then
            docker rm "$container_id" >/dev/null 2>&1 || true
        fi
        docker image rm "$image_tag" >/dev/null 2>&1 || true
        if [[ -n "$staging_directory" ]]; then
            rm -rf -- "$staging_directory"
        fi
    }
    trap cleanup_docker_build EXIT

    mkdir -p -- "$output_directory"
    echo "Building the native executable in Docker..."
    docker build \
        --file "$script_directory/Dockerfile" \
        --target artifact \
        --tag "$image_tag" \
        "$project_directory"

    container_id="$(docker create "$image_tag" /paceline)"
    staging_directory="$(mktemp -d "$output_directory/.paceline-docker.XXXXXX")"
    docker cp "$container_id:/paceline" "$staging_directory/paceline"
    chmod 0755 "$staging_directory/paceline"
    mv -f -- "$staging_directory/paceline" "$output_directory/paceline"
    echo "Native executable written to $output_directory/paceline."
)

select_native_executable() {
    local executable_path="$project_directory/build/native/nativeCompile/paceline"
    if [[ ! -x "$executable_path" ]]; then
        echo "Expected native executable at $executable_path." >&2
        echo "Build the artifact with: docker build --file deploy/Dockerfile --target artifact --tag paceline-native-build ." >&2
        usage >&2
        exit 1
    fi
    printf '%s\n' "$executable_path"
}

uninstall_application() {
    for command_name in getent groupdel id rm systemctl userdel; do
        if ! command -v "$command_name" >/dev/null 2>&1; then
            echo "Required command is not available: $command_name" >&2
            exit 1
        fi
    done

    local account_home_directory=""
    local account_entry=""
    if account_entry="$(getent passwd paceline)"; then
        IFS=: read -r _ _ _ _ _ account_home_directory _ <<< "$account_entry"
    fi

    local delete_configuration="no"
    if ! read -r -p "Delete $configuration_directory configuration and secrets too? [y/N] " delete_configuration; then
        echo "Uninstall cancelled."
        return 0
    fi
    case "$delete_configuration" in
        y|Y|yes|YES)
            delete_configuration="yes"
            ;;
        *)
            delete_configuration="no"
            ;;
    esac

    echo "The following Paceline resources will be deleted:"
    echo "  /etc/systemd/system/paceline.service (if present)"
    echo "  /etc/systemd/system/paceline.socket (if present)"
    echo "  /opt/paceline (installation directory, if present)"
    if [[ "$delete_configuration" == "yes" ]]; then
        echo "  /etc/paceline (configuration and external secrets, if present)"
    else
        echo "  /etc/paceline (preserved)"
    fi
    if [[ -n "$account_home_directory" && "$account_home_directory" != "/" ]]; then
        echo "  $account_home_directory (paceline account home, if present)"
    fi
    echo "  paceline system account (if present)"
    echo "  paceline system group (if present)"

    local confirmation
    if ! read -r -p "Continue with uninstall? [y/N] " confirmation; then
        echo "Uninstall cancelled."
        return 0
    fi
    case "$confirmation" in
        y|Y|yes|YES)
            ;;
        *)
            echo "Uninstall cancelled."
            return 0
            ;;
    esac

    if systemctl is-active --quiet paceline.socket; then
        systemctl stop paceline.socket
    fi
    if systemctl is-active --quiet paceline.service; then
        systemctl stop paceline.service
    fi
    if systemctl is-enabled --quiet paceline.socket; then
        systemctl disable paceline.socket
    fi

    rm -f -- /etc/systemd/system/paceline.service
    rm -f -- /etc/systemd/system/paceline.socket
    systemctl daemon-reload
    rm -rf -- "$install_directory"
    if [[ "$delete_configuration" == "yes" ]]; then
        rm -rf -- "$configuration_directory"
    fi

    if getent passwd paceline >/dev/null; then
        userdel --remove paceline
    fi
    if getent group paceline >/dev/null; then
        groupdel paceline
    fi

    echo "Paceline has been uninstalled."
}

action="install"
case "${1:-}" in
    "")
        ;;
    --help|-h)
        if (( $# != 1 )); then
            usage >&2
            exit 2
        fi
        usage
        exit 0
        ;;
    --uninstall)
        if (( $# != 1 )); then
            usage >&2
            exit 2
        fi
        action="uninstall"
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac

if [[ "$(id -u)" -ne 0 ]]; then
    if ! command -v sudo >/dev/null 2>&1; then
        echo "sudo is required to manage Paceline." >&2
        exit 1
    fi
    if [[ "$action" == "uninstall" ]]; then
        exec sudo "$0" --uninstall
    fi
    build_application
    exec sudo env PACELINE_INSTALL_BUILD_COMPLETED=1 "$0" "$@"
fi

if [[ "$action" == "uninstall" ]]; then
    uninstall_application
    exit 0
fi

if [[ "$build_completed" != "1" ]]; then
    if [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]]; then
        if ! command -v sudo >/dev/null 2>&1; then
            echo "sudo is required to build as $SUDO_USER." >&2
            exit 1
        fi
        exec sudo -u "$SUDO_USER" -- "$0" "$@"
    else
        build_application
    fi
fi

native_path="$(select_native_executable)"

for command_name in getent groupadd id install mktemp systemctl useradd usermod; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required command is not available: $command_name" >&2
        exit 1
    fi
done

if ! getent group bluetooth >/dev/null; then
    echo "The bluetooth group is missing; install BlueZ before installing Paceline." >&2
    exit 1
fi

if [[ ! -x "$native_path" ]]; then
    echo "Native executable is not executable: $native_path" >&2
    exit 1
fi

if [[ ! -r "$example_configuration" ]]; then
    echo "Example configuration is not readable: $example_configuration" >&2
    exit 1
fi

if [[ ! -r "$example_secrets" ]]; then
    echo "Example secrets file is not readable: $example_secrets" >&2
    exit 1
fi

if ! getent group paceline >/dev/null; then
    groupadd --system paceline
fi

if ! id -u paceline >/dev/null 2>&1; then
    useradd \
        --system \
        --gid paceline \
        --no-create-home \
        --shell /usr/sbin/nologin \
        paceline
fi

if [[ "$(id -u paceline)" == "0" ]]; then
    echo "The paceline account must not be root." >&2
    exit 1
fi

if [[ "$(id -gn paceline)" != "paceline" ]]; then
    echo "The existing paceline user must use the paceline primary group." >&2
    exit 1
fi

usermod --append --groups bluetooth paceline

install -d -o root -g root -m 0755 "$install_directory"
install -d -o root -g root -m 0755 "$configuration_directory"

if [[ ! -e "$configuration_directory/paceline.env" ]]; then
    install -o root -g paceline -m 0640 \
        "$example_secrets" \
        "$configuration_directory/paceline.env"
else
    chown root:paceline "$configuration_directory/paceline.env"
    chmod 0640 "$configuration_directory/paceline.env"
fi

if [[ ! -e "$configuration_directory/application.yml" ]]; then
    install -o root -g root -m 0644 \
        "$example_configuration" \
        "$configuration_directory/application.yml"
else
    chown root:root "$configuration_directory/application.yml"
    chmod 0644 "$configuration_directory/application.yml"
fi

temporary_native="$(mktemp "$install_directory/paceline.XXXXXX")"
trap 'rm -f -- "$temporary_native"' EXIT
install -o root -g root -m 0755 "$native_path" "$temporary_native"
mv -f -- "$temporary_native" "$install_directory/paceline"
trap - EXIT

install -o root -g root -m 0644 \
    "$script_directory/paceline.service" \
    /etc/systemd/system/paceline.service
install -o root -g root -m 0644 \
    "$script_directory/paceline.socket" \
    /etc/systemd/system/paceline.socket

systemctl daemon-reload
if systemctl is-active --quiet paceline.service; then
    systemctl stop paceline.service
fi
systemctl enable --now paceline.socket

echo "Paceline is installed."
echo "Application configuration: $configuration_directory/application.yml"
echo "External secrets: $configuration_directory/paceline.env"
echo "Replace the placeholder secrets with: sudoedit $configuration_directory/paceline.env"
echo "After changing secrets or application settings, restart: sudo systemctl restart paceline.socket"
echo "To change the HTTP port, edit /etc/systemd/system/paceline.socket (ListenStream), then run:"
echo "  sudo systemctl daemon-reload && sudo systemctl restart paceline.socket"
echo "Logs: journalctl -u paceline.service"
