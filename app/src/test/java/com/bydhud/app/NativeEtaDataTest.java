package com.bydhud.app;

import org.junit.Test;

import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class NativeEtaDataTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Test public void nativeModeSharesPerMetricScopeFallbackAndResolverDerivation() {
        long now = utc(2026, 10, 14, 10, 30, 30);
        long nextArrival = utc(2026, 10, 14, 10, 32, 5);
        DirectTbtFrame.TravelMetrics next = travel(
                nextArrival, -1L, 0L, -1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS);
        DirectTbtFrame.TravelMetrics whole = travel(
                -1L, 30L, -1L, -1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS);

        NativeEtaData data = NativeEtaData.from(frame(next, whole),
                options(HudPrefs.ROUTE_METRICS_WHOLE_ROUTE,
                        true, true, true, HudPrefs.ETA_OUTPUT_NATIVE), now);

        assertEquals(nextArrival, data.arrivalEpochMs);
        assertEquals(30L, data.remainingSeconds);
        assertEquals(0L, data.remainingMeters);
        assertEquals(HudEtaResolver.Provenance.DERIVED,
                HudEtaResolver.resolve(new DirectTbtFrame.TripMetrics(next, whole), now)
                        .nextStop.durationProvenance);
    }

    @Test public void durationSampleCanDeriveArrivalWithoutChangingItsSourceValue() {
        long now = utc(2026, 10, 14, 10, 30, 30);
        DirectTbtFrame.TravelMetrics next = travel(-1L, 61L, 4L, now,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS);

        NativeEtaData data = NativeEtaData.from(frame(next,
                        DirectTbtFrame.TravelMetrics.unavailable()),
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, true, HudPrefs.ETA_OUTPUT_NATIVE), now);

        assertEquals(utc(2026, 10, 14, 10, 32, 0), data.arrivalEpochMs);
        assertEquals(61L, data.remainingSeconds);
        assertEquals(4L, data.remainingMeters);
    }

    @Test public void nonNativeAndOffScopeReturnTheSharedEmptyValue() {
        DirectTbtFrame frame = frame(travel(utc(2026, 10, 14, 10, 0, 0),
                60L, 1L, -1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS),
                DirectTbtFrame.TravelMetrics.unavailable());

        assertSame(NativeEtaData.EMPTY, NativeEtaData.from(frame,
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, true, HudPrefs.ETA_OUTPUT_ALTERNATIVE), 0L));
        assertSame(NativeEtaData.EMPTY, NativeEtaData.from(frame,
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, true, HudPrefs.ETA_OUTPUT_OFF), 0L));
        assertSame(NativeEtaData.EMPTY, NativeEtaData.from(frame,
                options(HudPrefs.ROUTE_METRICS_OFF,
                        true, true, true, HudPrefs.ETA_OUTPUT_NATIVE), 0L));
    }

    @Test public void metricSwitchesRemoveOnlyTheirOwnNativeValues() {
        DirectTbtFrame frame = frame(travel(utc(2026, 10, 14, 10, 0, 0),
                0L, 5L, -1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS),
                DirectTbtFrame.TravelMetrics.unavailable());

        NativeEtaData data = NativeEtaData.from(frame,
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        false, true, false, HudPrefs.ETA_OUTPUT_NATIVE), 0L);

        assertEquals(-1L, data.arrivalEpochMs);
        assertEquals(-1L, data.remainingMeters);
        assertEquals(0L, data.remainingSeconds);
    }

    @Test public void timezoneControlsMidnightAndSourceOffsetOverridesDeviceZone() {
        long now = utc(2026, 10, 14, 23, 55, 0);
        long arrival = utc(2026, 10, 15, 0, 5, 0);
        NativeEtaData data = data(arrival,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                600L, 12_345L, now, HudPrefs.ETA_OUTPUT_NATIVE);

        NativeEtaFormatter.Formatted utc = format(data, now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted losAngeles = format(data, now,
                TimeZone.getTimeZone("America/Los_Angeles"), true, Locale.US);
        NativeEtaData sourceZoneData = data(arrival, 3600,
                600L, 12_345L, now, HudPrefs.ETA_OUTPUT_NATIVE);
        NativeEtaFormatter.Formatted sourceZone = format(
                sourceZoneData, now, UTC, true, Locale.US);

        assertEquals("00:05", utc.arrivalClock);
        assertEquals("Tomorrow", utc.arrivalQualifier);
        assertEquals(2, utc.arrivalDay);
        assertEquals("17:05", losAngeles.arrivalClock);
        assertEquals("", losAngeles.arrivalQualifier);
        assertEquals(1, losAngeles.arrivalDay);
        assertEquals("01:05", sourceZone.arrivalClock);
        assertEquals("", sourceZone.arrivalQualifier);
        assertEquals(1, sourceZone.arrivalDay);
        assertEquals(12_345, utc.distanceMeters);
    }

    @Test public void formatterSeparatesRoadClockQualifierFromChineseInstrumentText() {
        long now = utc(2026, 10, 14, 14, 0, 0);
        long arrival = utc(2026, 10, 14, 15, 5, 0);
        NativeEtaData data = data(arrival,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                3_900L, 0L, now, HudPrefs.ETA_OUTPUT_NATIVE);

        NativeEtaFormatter.Formatted clock24 = format(data, now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted clock12 = format(data, now, UTC, false, Locale.US);

        assertEquals("15:05", clock24.arrivalClock);
        assertEquals("", clock24.arrivalQualifier);
        assertEquals("预计今天 15:05到达", clock24.etaText);
        assertEquals(15, clock24.arrivalHour);
        assertEquals(0, clock24.dayPeriod);
        assertEquals("3:05", clock12.arrivalClock);
        assertEquals("PM", clock12.arrivalQualifier);
        assertEquals("预计今天下午 3:05到达", clock12.etaText);
        assertEquals(3, clock12.arrivalHour);
        assertEquals(4, clock12.dayPeriod);
        assertEquals(5, clock12.arrivalMinute);
        assertEquals(0, clock12.arrivalSecond);
        assertEquals(1, clock12.arrivalDay);

        NativeEtaData morningData = data(utc(2026, 10, 14, 8, 5, 0),
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                300L, -1L, utc(2026, 10, 14, 7, 0, 0),
                HudPrefs.ETA_OUTPUT_NATIVE);
        NativeEtaFormatter.Formatted clock12Morning = format(morningData,
                utc(2026, 10, 14, 7, 0, 0), UTC, false, Locale.US);
        assertEquals("AM", clock12Morning.arrivalQualifier);
        assertEquals("预计今天上午 8:05到达", clock12Morning.etaText);
        assertEquals(2, clock12Morning.dayPeriod);
    }

    @Test public void pastArrivalKeepsClockButDoesNotInventTodayForInstrument() {
        long now = utc(2026, 10, 14, 10, 0, 0);
        NativeEtaData data = data(utc(2026, 10, 13, 15, 5, 0), 0,
                60L, 0L, now, HudPrefs.ETA_OUTPUT_NATIVE);

        NativeEtaFormatter.Formatted formatted = format(data, now, UTC,
                true, Locale.US);

        assertEquals("15:05", formatted.arrivalClock);
        assertEquals(-1, formatted.arrivalDay);
        assertEquals("-1", formatted.etaText);
    }

    @Test public void arrivalDayCodesCoverAfterTomorrowAndWeekdays() {
        long now = utc(2026, 10, 14, 8, 0, 0);
        NativeEtaFormatter.Formatted dayAfter = format(data(
                        utc(2026, 10, 16, 8, 0, 0),
                        DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                        60L, -1L,
                        now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted monday = format(data(
                        utc(2026, 10, 19, 8, 0, 0),
                        DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                        60L, -1L,
                        now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted sunday = format(data(
                        utc(2026, 10, 18, 8, 0, 0),
                        DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                        60L, -1L,
                        now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);

        assertEquals(3, dayAfter.arrivalDay);
        assertEquals("预计后天 8:00到达", dayAfter.etaText);
        assertEquals("Friday", dayAfter.arrivalQualifier);
        assertEquals(4, monday.arrivalDay);
        assertEquals("预计周一 8:00到达", monday.etaText);
        assertEquals("Monday", monday.arrivalQualifier);
        assertEquals(10, sunday.arrivalDay);
        assertEquals("预计周日 8:00到达", sunday.etaText);
        assertEquals("Sunday", sunday.arrivalQualifier);
    }

    @Test public void durationMatchesOemMinuteRoundingAndMultiDayComponents() {
        long now = utc(2026, 10, 14, 8, 0, 0);
        NativeEtaFormatter.Formatted zero = format(data(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                0L, 0L, now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted beforeHalfMinute = format(data(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                29L, 12_345L, now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted halfMinute = format(data(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                30L, 12_345L, now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted oneHour = format(data(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                3_600L, -1L, now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted oneHourAndOneMinute = format(data(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                3_660L, -1L, now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted multiday = format(data(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                90_900L, -1L, now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);

        assertEquals("<1分钟", zero.routeRemainTimeAuto);
        assertEquals(1, zero.remainingUnder30);
        assertEquals(1, zero.durationMinutes);
        assertEquals(0, zero.distanceMeters);
        assertEquals("<1分钟", beforeHalfMinute.routeRemainTimeAuto);
        assertEquals(1, beforeHalfMinute.remainingUnder30);
        assertEquals("1分钟", halfMinute.routeRemainTimeAuto);
        assertEquals(0, halfMinute.remainingUnder30);
        assertEquals("1小时", oneHour.routeRemainTimeAuto);
        assertEquals(1, oneHour.durationHours);
        assertEquals(0, oneHour.durationMinutes);
        assertEquals("1时1分", oneHourAndOneMinute.routeRemainTimeAuto);
        assertEquals(1, oneHourAndOneMinute.durationHours);
        assertEquals(1, oneHourAndOneMinute.durationMinutes);
        assertEquals("1天1时", multiday.routeRemainTimeAuto);
        assertEquals(1, multiday.durationDays);
        assertEquals(1, multiday.durationHours);
        assertEquals(0, multiday.durationMinutes);
    }

    @Test public void unknownStringsAndOutOfRangeInstrumentValuesUseSentinels() {
        long now = utc(2026, 10, 14, 8, 0, 0);
        NativeEtaFormatter.Formatted unknown = format(data(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                -1L, -1L, now, HudPrefs.ETA_OUTPUT_NATIVE), now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted overflow = format(data(-1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                Long.MAX_VALUE, Long.MAX_VALUE, now, HudPrefs.ETA_OUTPUT_NATIVE),
                now, UTC, true, Locale.US);

        assertEquals("-1", unknown.routeRemainTimeAuto);
        assertEquals("-1", unknown.etaText);
        assertEquals("", unknown.arrivalClock);
        assertEquals("", unknown.arrivalQualifier);
        assertEquals(-1, unknown.durationDays);
        assertEquals(-1, unknown.durationHours);
        assertEquals(-1, unknown.durationMinutes);
        assertEquals(-1, unknown.remainingUnder30);
        assertEquals(-1, unknown.distanceMeters);
        assertEquals("-1", overflow.routeRemainTimeAuto);
        assertEquals(-1, overflow.durationDays);
        assertEquals(-1, overflow.distanceMeters);
    }

    @Test public void formattedArrivalAndDurationRemainIndependentlyMissing() {
        long now = utc(2026, 10, 14, 9, 0, 0);
        DirectTbtFrame.TravelMetrics timeOnly = travel(-1L,
                3_600L, -1L, -1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS);
        NativeEtaData noArrival = NativeEtaData.from(frame(timeOnly,
                        DirectTbtFrame.TravelMetrics.unavailable()),
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        false, true, false, HudPrefs.ETA_OUTPUT_NATIVE), now);
        DirectTbtFrame.TravelMetrics arrivalOnly = travel(
                utc(2026, 10, 14, 10, 0, 0), -1L, -1L, -1L,
                DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS);
        NativeEtaData noDuration = NativeEtaData.from(frame(arrivalOnly,
                        DirectTbtFrame.TravelMetrics.unavailable()),
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, false, false, HudPrefs.ETA_OUTPUT_NATIVE), now);

        NativeEtaFormatter.Formatted durationOnly = format(
                noArrival, now, UTC, true, Locale.US);
        NativeEtaFormatter.Formatted arrivalOnlyOutput = format(
                noDuration, now, UTC, true, Locale.US);

        assertEquals("-1", durationOnly.etaText);
        assertEquals("1小时", durationOnly.routeRemainTimeAuto);
        assertEquals(-1, durationOnly.arrivalDay);
        assertEquals(-1, durationOnly.distanceMeters);
        assertEquals("10:00", arrivalOnlyOutput.arrivalClock);
        assertEquals("预计今天 10:00到达", arrivalOnlyOutput.etaText);
        assertEquals("-1", arrivalOnlyOutput.routeRemainTimeAuto);
        assertEquals(-1, arrivalOnlyOutput.durationDays);
        assertEquals(-1, arrivalOnlyOutput.durationHours);
        assertEquals(-1, arrivalOnlyOutput.durationMinutes);
    }

    @Test public void alternativeStreetFormattingStaysActiveOnlyInAlternativeMode() {
        long now = utc(2026, 10, 14, 9, 0, 0);
        DirectTbtFrame frame = frame(travel(utc(2026, 10, 14, 10, 0, 0),
                3_600L, 1_234L, now, 0),
                DirectTbtFrame.TravelMetrics.unavailable());

        HudEtaText alternative = HudEtaText.from(frame,
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, true, HudPrefs.ETA_OUTPUT_ALTERNATIVE), now);
        HudEtaText nativeOutput = HudEtaText.from(frame,
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, true, HudPrefs.ETA_OUTPUT_NATIVE), now);

        assertEquals("10:00", alternative.arrival);
        assertEquals("1 h 0 min", alternative.duration);
        assertEquals("1.2 km", alternative.remainingDistance);
        assertSame(HudEtaText.EMPTY, nativeOutput);
        assertSame(HudEtaText.EMPTY, HudEtaText.from(frame,
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, true, HudPrefs.ETA_OUTPUT_OFF), now));
    }

    private static NativeEtaData data(long arrival, int offsetSeconds,
            long seconds, long meters, long now, int outputMode) {
        DirectTbtFrame.TravelMetrics next = travel(
                arrival, seconds, meters, -1L, offsetSeconds);
        return NativeEtaData.from(frame(next,
                        DirectTbtFrame.TravelMetrics.unavailable()),
                options(HudPrefs.ROUTE_METRICS_NEXT_STOP,
                        true, true, true, outputMode), now);
    }

    private static NativeEtaFormatter.Formatted format(NativeEtaData data,
            long now, TimeZone zone, boolean is24Hour, Locale locale) {
        return NativeEtaFormatter.format(data, now, zone, is24Hour, locale);
    }

    private static DirectTbtPayload.Options options(int scope,
            boolean showEta, boolean showTime, boolean showDistance, int outputMode) {
        return new DirectTbtPayload.Options(false, false, false, false,
                false, false, false, scope, showEta, showTime, showDistance,
                HudPrefs.SPEED_LIMIT_OFF, HudPrefs.SPEED_LIMIT_FALLBACK_OFF,
                5, null).withEtaOutputMode(outputMode);
    }

    private static DirectTbtFrame frame(
            DirectTbtFrame.TravelMetrics next, DirectTbtFrame.TravelMetrics whole) {
        return new DirectTbtFrame(0, 0, 0, 0, "", "", "", null, null,
                Collections.emptyList(), DirectTbtFrame.AlertOverlay.inactive(),
                new DirectTbtFrame.TripMetrics(next, whole));
    }

    private static DirectTbtFrame.TravelMetrics travel(long arrival, long seconds,
            long meters, long sampleWallMs, int offsetSeconds) {
        return new DirectTbtFrame.TravelMetrics(arrival, offsetSeconds,
                seconds, meters, sampleWallMs);
    }

    private static long utc(int year, int month, int day,
            int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance(UTC, Locale.US);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        return calendar.getTimeInMillis();
    }
}
