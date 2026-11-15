package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.model.MetadataField

open class MetadataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown before anything is written when an update would replace a tag the file already has
 * and the caller did not confirm that overwrite.
 */
class OverwriteNotConfirmedException(val fields: Set<MetadataField>) :
    MetadataException("These tags already exist and need confirmation to overwrite: $fields")

class UnsupportedMediaException(message: String) : MetadataException(message)

class MalformedMediaException(message: String, cause: Throwable? = null) : MetadataException(message, cause)
