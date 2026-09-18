import assert from "node:assert/strict";
import test from "node:test";
import {
    adjustManualErgTargetWatts,
    initialManualErgTargetWatts,
    maximumFtmsPowerWatts,
    minimumFtmsPowerWatts,
} from "../src/lib/manual-erg.ts";

test("starts manual ERG at half of the effective FTP", () => {
    // given an indoor FTP and a different cycling FTP:
    const profile = {
        indoorFtpWatts: 251,
        ftpWatts: 300,
    };

    // when the manual ERG starting target is calculated:
    const target = initialManualErgTargetWatts(profile);

    // then the configured indoor FTP is used and fractional watts are floored:
    assert.equal(target, 125);
});

test("falls back to cycling FTP when indoor FTP is unavailable", () => {
    // given a profile without an indoor FTP:
    const profile = {
        indoorFtpWatts: null,
        ftpWatts: 201,
    };

    // when the manual ERG starting target is calculated:
    const target = initialManualErgTargetWatts(profile);

    // then half of the cycling FTP is used:
    assert.equal(target, 100);
});

test("does not invent a manual ERG target without usable FTP", () => {
    // given a profile without a positive FTP:
    const profile = {
        indoorFtpWatts: null,
        ftpWatts: 0,
    };

    // when the manual ERG starting target is calculated:
    const target = initialManualErgTargetWatts(profile);

    // then no target is returned:
    assert.equal(target, null);
});

test("adjusts manual ERG by one watt within the FTMS range", () => {
    // given a current manual ERG target:
    const currentTarget = 125;

    // when the plus and minus controls are applied:
    const increased = adjustManualErgTargetWatts(currentTarget, 1);
    const decreased = adjustManualErgTargetWatts(currentTarget, -1);

    // then each control changes the target by exactly one watt:
    assert.equal(increased, 126);
    assert.equal(decreased, 124);
});

test("clamps manual ERG adjustments to the FTMS range", () => {
    // given targets at the representable boundaries:
    // when a control tries to move beyond either boundary:
    const aboveMaximum = adjustManualErgTargetWatts(maximumFtmsPowerWatts, 1);
    const belowMinimum = adjustManualErgTargetWatts(minimumFtmsPowerWatts, -1);

    // then the target remains representable:
    assert.equal(aboveMaximum, maximumFtmsPowerWatts);
    assert.equal(belowMinimum, minimumFtmsPowerWatts);
});
