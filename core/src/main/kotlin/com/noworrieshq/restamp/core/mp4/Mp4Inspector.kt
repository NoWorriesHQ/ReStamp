package com.noworrieshq.restamp.core.mp4

import com.noworrieshq.restamp.core.io.RandomAccessIo

object Mp4Inspector {
    /** True when a top-level `uuid` box holds a C2PA (Content Credentials) manifest. */
    fun hasContentCredentials(io: RandomAccessIo): Boolean =
        BoxParser.topLevel(io).any { box ->
            box.type == "uuid" && box.size > 40 &&
                String(io.read(box.offset, minOf(box.size, 256L).toInt()), Charsets.ISO_8859_1).contains("c2pa")
        }
}
