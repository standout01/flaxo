package org.flaxo.common.data

/**
 * Multiplatform date time class.
 */
expect class DateTime {

    companion object {

        /**
         * Parse date time from the string of the following format:
         * 2018-03-08T15:00:00.000
         */
        fun fromDateTimeString(string: String): DateTime

        /**
         * Returns current date time instance.
         */
        fun now(): DateTime
    }

    /**
     * Formats date time to a string of the following format:
     * 2018-03-08T15:00:00.000
     */
    fun toDateTimeString(): String

    /**
     * Formats date time to a string of the following format:
     * 2018-03-08
     */
    fun toDateString(): String

    operator fun compareTo(other: DateTime): Int
}
