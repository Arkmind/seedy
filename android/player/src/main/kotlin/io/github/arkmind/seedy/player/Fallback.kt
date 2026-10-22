package io.github.arkmind.seedy.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.jellyfin.sdk.model.api.PlayMethod

/** The part of a stream this device failed on: the picture, the sound, or the file around them. */
enum class FailedPart { Video, Audio, File }

/**
 * The part of the stream behind a player error, from its code, the format the failing renderer had and whether
 * reading the stream broke ([unreadable]). Null when the error isn't about the stream itself (the network, the
 * server's answer).
 */
fun failedPart(errorCode: Int, rendererMime: String?, unreadable: Boolean = false): FailedPart? = when {
    MimeTypes.isVideo(rendererMime) -> FailedPart.Video
    MimeTypes.isAudio(rendererMime) || errorCode in AudioOutputErrors -> FailedPart.Audio
    errorCode in DecoderErrors -> FailedPart.Video
    errorCode in ReadErrors || unreadable || rendererMime != null -> FailedPart.File
    else -> null
}

/**
 * What to ask the server for after this device failed on a stream played with [options] and [method], or null once
 * it has all been tried: the server converting the part it couldn't decode (the picture to H.264, the sound to AAC),
 * repackaging a file played as it is, and at last converting both.
 */
fun nextAttempt(options: StreamOptions, method: PlayMethod, part: FailedPart?): StreamOptions? = when {
    part == FailedPart.Video && !options.convertVideo -> options.copy(convertVideo = true)
    part == FailedPart.Audio && !options.convertAudio -> options.copy(convertAudio = true)
    method == PlayMethod.DIRECT_PLAY -> options.copy(forceTranscode = true)
    part != null && !(options.convertVideo && options.convertAudio) -> options.copy(convertVideo = true, convertAudio = true)
    else -> null
}

private val DecoderErrors = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
)

private val AudioOutputErrors = setOf(
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
)

private val ReadErrors = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
)
