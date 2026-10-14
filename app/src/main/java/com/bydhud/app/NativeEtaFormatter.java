package com.bydhud.app;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/** Pure formatter for OEM RoadInfo ETA and Instrument ETA inputs. */
final class NativeEtaFormatter {
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final int MAX_UTC_OFFSET_SECONDS = 18 * 60 * 60;
    private static final String[] CHINESE_WEEKDAYS = {
            "", "周日", "周一", "周二", "周三", "周四", "周五", "周六"
    };

    private NativeEtaFormatter() {
    }

    static Formatted format(NativeEtaData data, long nowWallMs,
            TimeZone deviceZone, boolean is24Hour, Locale locale) {
        NativeEtaData safe = data == null ? NativeEtaData.EMPTY : data;
        Locale safeLocale = locale == null ? Locale.US : locale;
        TimeZone zone = zone(safe, deviceZone);
        Duration duration = duration(safe.remainingSeconds);
        int distanceMeters = safe.remainingMeters >= 0L
                && safe.remainingMeters <= Integer.MAX_VALUE
                ? (int) safe.remainingMeters : -1;
        if (safe.arrivalEpochMs <= 0L) {
            return new Formatted("", "", duration.text, "-1",
                    duration.days, duration.hours, duration.minutes,
                    duration.under30, distanceMeters,
                    -1, -1, -1, -1, -1);
        }

        Calendar arrival = Calendar.getInstance(zone, safeLocale);
        arrival.setTimeInMillis(safe.arrivalEpochMs);
        long dayDifference = nowWallMs < 0L ? Long.MIN_VALUE
                : localDay(safe.arrivalEpochMs, zone) - localDay(nowWallMs, zone);
        int arrivalDay = arrivalDay(dayDifference, arrival);
        int hour24 = arrival.get(Calendar.HOUR_OF_DAY);
        int hour12 = arrival.get(Calendar.HOUR);
        if (hour12 == 0) hour12 = 12;
        int hour = is24Hour ? hour24 : hour12;
        int minute = arrival.get(Calendar.MINUTE);
        int dayPeriod = is24Hour ? 0
                : arrival.get(Calendar.AM_PM) == Calendar.AM ? 2 : 4;
        String clock = (is24Hour ? twoDigits(hour24) : Integer.toString(hour12))
                + ":" + twoDigits(minute);
        String dayQualifier = roadDayQualifier(dayDifference, arrival, safeLocale);
        String period = is24Hour ? "" : amPm(arrival, safeLocale);
        String qualifier = dayQualifier.isEmpty() ? period
                : period.isEmpty() ? dayQualifier : dayQualifier + " " + period;
        String etaText = machineEtaText(dayDifference, arrival, hour, minute,
                is24Hour);

        return new Formatted(clock, qualifier, duration.text, etaText,
                duration.days, duration.hours, duration.minutes,
                duration.under30, distanceMeters,
                arrivalDay, hour, minute, 0, dayPeriod);
    }

    private static Duration duration(long seconds) {
        if (seconds < 0L) return Duration.MISSING;
        long minutes = seconds / 60L + (seconds % 60L >= 30L ? 1L : 0L);
        long days = minutes / 1440L;
        if (days > Integer.MAX_VALUE) return Duration.MISSING;
        int hours = (int) ((minutes % 1440L) / 60L);
        int minutePart = (int) (minutes % 60L);
        int durationHours = days > 0L || minutes >= 60L ? hours : 0;
        int durationMinutes = days > 0L ? 0 : minutes == 0L ? 1 : minutePart;
        int durationDays = (int) days;
        int under30 = durationDays == 0 && durationHours == 0
                && durationMinutes == 1 && seconds < 30L ? 1 : 0;

        String text;
        if (minutes == 0L) {
            text = "<1分钟";
        } else if (days > 0L) {
            text = durationDays + "天" + (hours == 0 ? "" : hours + "时");
        } else if (minutes < 60L) {
            text = minutes + "分钟";
        } else if (minutePart == 0) {
            text = hours + "小时";
        } else {
            text = hours + "时" + minutePart + "分";
        }
        return new Duration(durationDays, durationHours, durationMinutes,
                under30, text);
    }

    private static int arrivalDay(long dayDifference, Calendar arrival) {
        if (dayDifference == 0L) return 1;
        if (dayDifference == 1L) return 2;
        if (dayDifference == 2L) return 3;
        if (dayDifference > 2L) {
            int dayOfWeek = arrival.get(Calendar.DAY_OF_WEEK);
            return dayOfWeek == Calendar.SUNDAY ? 10 : dayOfWeek + 2;
        }
        return -1;
    }

    private static String roadDayQualifier(
            long dayDifference, Calendar arrival, Locale locale) {
        if (dayDifference == 1L) return tomorrow(locale);
        if (dayDifference > 1L) {
            SimpleDateFormat weekday = new SimpleDateFormat("EEEE", locale);
            weekday.setTimeZone(arrival.getTimeZone());
            return weekday.format(arrival.getTime());
        }
        return "";
    }

    private static String machineEtaText(long dayDifference, Calendar arrival,
            int hour, int minute, boolean is24Hour) {
        if (dayDifference < 0L) return "-1";
        StringBuilder text = new StringBuilder("预计");
        if (dayDifference == 0L) text.append("今天");
        else if (dayDifference == 1L) text.append("明天");
        else if (dayDifference == 2L) text.append("后天");
        else if (dayDifference > 2L) {
            text.append(CHINESE_WEEKDAYS[arrival.get(Calendar.DAY_OF_WEEK)]);
        }
        if (!is24Hour) {
            text.append(arrival.get(Calendar.AM_PM) == Calendar.AM ? "上午" : "下午");
        }
        return text.append(' ').append(hour).append(':').append(twoDigits(minute))
                .append("到达").toString();
    }

    private static String amPm(Calendar arrival, Locale locale) {
        String[] values = DateFormatSymbols.getInstance(locale).getAmPmStrings();
        String value = values[arrival.get(Calendar.AM_PM)];
        return value == null || value.isEmpty()
                ? (arrival.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM")
                : value;
    }

    private static String tomorrow(Locale locale) {
        switch (locale.getLanguage()) {
            case "uk":
            case "ru":
                return "Завтра";
            case "zh":
                return "明天";
            default:
                return "Tomorrow";
        }
    }

    private static long localDay(long epochMs, TimeZone zone) {
        long offset = zone.getOffset(epochMs);
        long localMs;
        if (offset > 0 && epochMs > Long.MAX_VALUE - offset) {
            localMs = Long.MAX_VALUE;
        } else if (offset < 0 && epochMs < Long.MIN_VALUE - offset) {
            localMs = Long.MIN_VALUE;
        } else {
            localMs = epochMs + offset;
        }
        return Math.floorDiv(localMs, MILLIS_PER_DAY);
    }

    private static TimeZone zone(NativeEtaData data, TimeZone deviceZone) {
        int offsetSeconds = data.arrivalOffsetSeconds;
        if (offsetSeconds != DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS
                && Math.abs((long) offsetSeconds) <= MAX_UTC_OFFSET_SECONDS) {
            return new SimpleTimeZone((int) (offsetSeconds * 1000L), "ETA source");
        }
        return deviceZone == null ? TimeZone.getTimeZone("GMT")
                : (TimeZone) deviceZone.clone();
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    static final class Formatted {
        final String arrivalClock;
        final String arrivalQualifier;
        final String routeRemainTimeAuto;
        final String etaText;
        final int durationDays;
        final int durationHours;
        final int durationMinutes;
        final int remainingUnder30;
        final int distanceMeters;
        final int arrivalDay;
        final int arrivalHour;
        final int arrivalMinute;
        final int arrivalSecond;
        final int dayPeriod;

        Formatted(String arrivalClock, String arrivalQualifier,
                String routeRemainTimeAuto, String etaText,
                int durationDays, int durationHours, int durationMinutes,
                int remainingUnder30, int distanceMeters,
                int arrivalDay, int arrivalHour, int arrivalMinute,
                int arrivalSecond, int dayPeriod) {
            this.arrivalClock = arrivalClock;
            this.arrivalQualifier = arrivalQualifier;
            this.routeRemainTimeAuto = routeRemainTimeAuto;
            this.etaText = etaText;
            this.durationDays = durationDays;
            this.durationHours = durationHours;
            this.durationMinutes = durationMinutes;
            this.remainingUnder30 = remainingUnder30;
            this.distanceMeters = distanceMeters;
            this.arrivalDay = arrivalDay;
            this.arrivalHour = arrivalHour;
            this.arrivalMinute = arrivalMinute;
            this.arrivalSecond = arrivalSecond;
            this.dayPeriod = dayPeriod;
        }
    }

    private static final class Duration {
        static final Duration MISSING = new Duration(-1, -1, -1, -1, "-1");

        final int days;
        final int hours;
        final int minutes;
        final int under30;
        final String text;

        Duration(int days, int hours, int minutes, int under30, String text) {
            this.days = days;
            this.hours = hours;
            this.minutes = minutes;
            this.under30 = under30;
            this.text = text;
        }
    }
}
