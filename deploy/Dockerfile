FROM node:22-bookworm-slim AS frontend-build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/index.html frontend/vite.config.ts frontend/tsconfig.json ./
COPY frontend/src ./src
COPY frontend/public ./public
RUN npm run build

FROM ghcr.io/graalvm/native-image-community:25 AS native-build

WORKDIR /workspace

COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew
COPY src/main ./src/main
COPY --from=frontend-build /workspace/frontend/dist/ ./src/main/resources/static/

RUN ./gradlew nativeCompile

FROM scratch AS artifact

COPY --from=native-build /workspace/build/native/nativeCompile/paceline /paceline
