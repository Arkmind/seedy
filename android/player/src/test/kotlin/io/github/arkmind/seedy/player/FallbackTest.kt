package io.github.arkmind.seedy.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.jellyfin.sdk.model.api.PlayMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackTest {
    @Test
    fun partFromTheRendererFormat() {
        assertEquals(FailedPart.Video, failedPart(PlaybackException.ERROR_CODE_DECODING_FAILED, MimeTypes.VIDEO_H265))
        assertEquals(FailedPart.Audio, failedPart(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, MimeTypes.AUDIO_TRUEHD))
        assertEquals(FailedPart.Audio, failedPart(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, null))
        assertEquals(FailedPart.File, failedPart(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null))
        assertEquals(FailedPart.File, failedPart(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null, unreadable = true))
        assertNull(failedPart(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null))
    }

    @Test
    fun undecodedVideoIsConvertedNotCopied() {
        val first = nextAttempt(StreamOptions(), PlayMethod.DIRECT_PLAY, FailedPart.Video)!!
        assertEquals(StreamOptions(convertVideo = true), first)
        // Converting to H.264 didn't help either: everything is converted, then it stops.
        val second = nextAttempt(first, PlayMethod.TRANSCODE, FailedPart.Video)!!
        assertEquals(StreamOptions(convertVideo = true, convertAudio = true), second)
        assertNull(nextAttempt(second, PlayMethod.TRANSCODE, FailedPart.Video))
    }

    @Test
    fun copiedVideoThatFailsIsConverted() {
        assertEquals(StreamOptions(convertVideo = true), nextAttempt(StreamOptions(forceTranscode = false), PlayMethod.DIRECT_STREAM, FailedPart.Video))
    }

    @Test
    fun unreadableFileIsRepackagedFirst() {
        val first = nextAttempt(StreamOptions(), PlayMethod.DIRECT_PLAY, FailedPart.File)!!
        assertEquals(StreamOptions(forceTranscode = true), first)
        assertEquals(StreamOptions(forceTranscode = true, convertVideo = true, convertAudio = true), nextAttempt(first, PlayMethod.DIRECT_STREAM, FailedPart.File))
    }

    @Test
    fun serverErrorsAreNotRetriedOnceConverting() {
        assertEquals(StreamOptions(forceTranscode = true), nextAttempt(StreamOptions(), PlayMethod.DIRECT_PLAY, null))
        assertNull(nextAttempt(StreamOptions(forceTranscode = true), PlayMethod.TRANSCODE, null))
    }
}
