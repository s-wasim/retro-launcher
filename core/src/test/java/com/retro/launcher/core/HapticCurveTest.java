package com.retro.launcher.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HapticCurveTest {

    @Test public void thereAreExactlyEightBuckets() {
        assertEquals(8, HapticCurve.BUCKETS);
    }

    @Test public void bucketsSpanZeroToSeven() {
        assertEquals(0, HapticCurve.bucket(0f));
        assertEquals(0, HapticCurve.bucket(0.124f));
        assertEquals(1, HapticCurve.bucket(0.125f));
        assertEquals(7, HapticCurve.bucket(0.9f));
        assertEquals(7, HapticCurve.bucket(1f));
    }

    @Test public void progressOutsideZeroToOneClampsRatherThanEscapingTheRange() {
        assertEquals(0, HapticCurve.bucket(-5f));
        assertEquals(7, HapticCurve.bucket(5f));
        assertEquals(0, HapticCurve.bucket(Float.NaN));
    }

    @Test public void amplitudeStartsAtTheFloorAndEndsAtFull() {
        assertEquals(HapticCurve.FLOOR_AMPLITUDE, HapticCurve.amplitude(0f));
        assertEquals(HapticCurve.MAX_AMPLITUDE, HapticCurve.amplitude(1f));
    }

    @Test public void amplitudeNeverDecreasesAsProgressRises() {
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i <= 1000; i++) {
            int a = HapticCurve.amplitude(i / 1000f);
            assertTrue("amplitude fell at progress " + (i / 1000f), a >= previous);
            previous = a;
        }
    }

    @Test public void amplitudeStaysWithinTheVibratorsLegalRange() {
        for (int i = 0; i <= 1000; i++) {
            int a = HapticCurve.amplitude(i / 1000f);
            assertTrue(a >= 1 && a <= 255);
        }
    }

    @Test public void aFullDragCommandsTheVibratorAtMostEightTimes() {
        // The reason bucketing exists. Walk a 60fps, 2-second drag and count
        // how many frames would actually change the bucket.
        int changes = 0;
        int last = -1;
        for (int frame = 0; frame <= 120; frame++) {
            int b = HapticCurve.bucket(frame / 120f);
            if (b != last) { changes++; last = b; }
        }
        assertEquals(8, changes);
    }

    @Test public void theRampIsSquaredNotLinear() {
        // Halfway through, a squared ramp is well below the linear midpoint.
        int mid = HapticCurve.amplitude(0.5f);
        int linearMid = (HapticCurve.FLOOR_AMPLITUDE + HapticCurve.MAX_AMPLITUDE) / 2;
        assertTrue("expected " + mid + " < " + linearMid, mid < linearMid);
    }

    @Test public void bucketIndexesOutsideTheRangeClamp() {
        assertEquals(HapticCurve.FLOOR_AMPLITUDE, HapticCurve.amplitudeForBucket(-3));
        assertEquals(HapticCurve.MAX_AMPLITUDE, HapticCurve.amplitudeForBucket(99));
    }
}
