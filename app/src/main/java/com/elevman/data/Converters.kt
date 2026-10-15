package com.elevman.data

import androidx.room.TypeConverter
import java.time.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Room type converters. Both types are stored as text: a [LocalDate] as its ISO-8601 form
 * (`2026-10-15`) and a [ReviewStatus] by its enum name.
 */
class Converters {
    @TypeConverter
    fun localDateToString(value: LocalDate): String = value.toString()

    @TypeConverter
    fun stringToLocalDate(value: String): LocalDate = LocalDate.parse(value)

    @TypeConverter
    fun reviewStatusToString(value: ReviewStatus): String = value.name

    @TypeConverter
    fun stringToReviewStatus(value: String): ReviewStatus = ReviewStatus.valueOf(value)
}

/**
 * kotlinx.serialization serializer that writes a [LocalDate] as an ISO-8601 string, so the backup
 * JSON carries the same plain text that Room stores in the database.
 */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
