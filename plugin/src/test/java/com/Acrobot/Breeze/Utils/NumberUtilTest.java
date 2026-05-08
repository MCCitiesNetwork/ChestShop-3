package com.Acrobot.Breeze.Utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NumberUtilTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "-1", "2147483647", "-2147483648"})
    void isInteger_acceptsValidInts(String s) {
        assertThat(NumberUtil.isInteger(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1.0", "1e10", "abc", "2147483648", "-2147483649", "1 "})
    void isInteger_rejectsNonInts(String s) {
        assertThat(NumberUtil.isInteger(s)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "-1", "1.0", "1e10", "1.5", "-3.14"})
    void isFloat_acceptsValidFloats(String s) {
        assertThat(NumberUtil.isFloat(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1.0.0", "--1"})
    void isFloat_rejectsNonFloats(String s) {
        assertThat(NumberUtil.isFloat(s)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1.0", "1e10", "-3.14", "1e-10", "Infinity", "NaN"})
    void isDouble_acceptsValidDoubles(String s) {
        assertThat(NumberUtil.isDouble(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1.0.0"})
    void isDouble_rejectsNonDoubles(String s) {
        assertThat(NumberUtil.isDouble(s)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "32767", "-32768"})
    void isShort_acceptsValidShorts(String s) {
        assertThat(NumberUtil.isShort(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"32768", "-32769", "1.0", "abc"})
    void isShort_rejectsNonShorts(String s) {
        assertThat(NumberUtil.isShort(s)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "-1", "9223372036854775807", "-9223372036854775808"})
    void isLong_acceptsValidLongs(String s) {
        assertThat(NumberUtil.isLong(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "1.0", "abc"})
    void isLong_rejectsNonLongs(String s) {
        assertThat(NumberUtil.isLong(s)).isFalse();
    }

    @Test
    void isEnchantment_acceptsParseableLongs() {
        // The implementation calls Long.parseLong(s, 32). What Java accepts as a
        // radix-32 digit character is JVM-implementation-defined for the upper
        // alphabet, so we only assert obviously-valid + obviously-invalid cases.
        assertThat(NumberUtil.isEnchantment("0")).isTrue();
        assertThat(NumberUtil.isEnchantment("af")).isTrue();
        assertThat(NumberUtil.isEnchantment("123")).isTrue();
    }

    @Test
    void isEnchantment_rejectsNonDigitText() {
        assertThat(NumberUtil.isEnchantment("")).isFalse();
        assertThat(NumberUtil.isEnchantment(" ")).isFalse();
        assertThat(NumberUtil.isEnchantment("hello world")).isFalse();
        assertThat(NumberUtil.isEnchantment("!@#")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "1.234, 1.24",     // rounds .234 → .24 (ceil at 2dp)
        "1.0,   1.00",
        "0.0,   0.0",
        "1.999, 2.0",
        "-1.234, -1.23",   // ceil-toward-positive-infinity
    })
    void roundUp_roundsToTwoDecimalsCeiling(double in, double expected) {
        assertThat(NumberUtil.roundUp(in)).isCloseTo(expected, within(1e-9));
    }

    @ParameterizedTest
    @CsvSource({
        "1.999, 1.99",
        "1.0,   1.00",
        "1.234, 1.23",
        "-1.234, -1.24",   // floor toward negative infinity
    })
    void roundDown_roundsToTwoDecimalsFloor(double in, double expected) {
        assertThat(NumberUtil.roundDown(in)).isCloseTo(expected, within(1e-9));
    }

    @ParameterizedTest
    @CsvSource({
        "0,    '00:00'",
        "59,   '00:59'",
        "60,   '01:00'",
        "3599, '59:59'",
        "3600, '00:00'",   // wraps within hour as documented (mm:ss)
        "3661, '01:01'",
    })
    void toTime_formatsAsMinutesAndSeconds(int seconds, String expected) {
        assertThat(NumberUtil.toTime(seconds)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "1, I", "2, II", "3, III", "4, IV", "5, V",
        "6, VI", "7, VII", "8, VIII", "9, IX",
    })
    void toRoman_returnsRomanForOneThroughNine(int n, String expected) {
        assertThat(NumberUtil.toRoman(n)).isEqualTo(expected);
    }

    @Test
    void toRoman_fallsBackToDecimalOutsideRange() {
        // The doc says it only handles 1-9 (enchantment levels).
        assertThat(NumberUtil.toRoman(0)).isEqualTo("0");
        assertThat(NumberUtil.toRoman(10)).isEqualTo("10");
        assertThat(NumberUtil.toRoman(-3)).isEqualTo("-3");
    }

    @Test
    void toInt_clampsLongOverflowToMaxValue() {
        assertThat(NumberUtil.toInt(123L)).isEqualTo(123);
        assertThat(NumberUtil.toInt((long) Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(NumberUtil.toInt((long) Integer.MAX_VALUE + 1)).isEqualTo(Integer.MAX_VALUE);
        assertThat(NumberUtil.toInt(Long.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void toInt_passesNegativeLongsThroughWithoutClamp() {
        // The cast is unsigned-to-int; negative longs that fit in int round-trip.
        assertThat(NumberUtil.toInt(-1L)).isEqualTo(-1);
        assertThat(NumberUtil.toInt((long) Integer.MIN_VALUE)).isEqualTo(Integer.MIN_VALUE);
    }
}
