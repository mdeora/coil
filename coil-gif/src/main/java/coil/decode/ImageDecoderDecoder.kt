@file:Suppress("unused")

package coil.decode

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build.VERSION_CODES.P
import androidx.annotation.RequiresApi
import androidx.core.graphics.decodeDrawable
import androidx.core.util.component1
import androidx.core.util.component2
import coil.bitmappool.BitmapPool
import coil.drawable.ScaleDrawable
import coil.extension.repeatCount
import coil.size.PixelSize
import coil.size.Size
import okio.BufferedSource
import okio.sink
import kotlin.math.roundToInt

/**
 * A [Decoder] that uses [ImageDecoder] to decode GIFs and animated WebPs on Android P and above.
 */
@RequiresApi(P)
class ImageDecoderDecoder : Decoder {

    companion object {
        const val REPEAT_COUNT_KEY = GifDecoder.REPEAT_COUNT_KEY
    }

    override fun handles(source: BufferedSource, mimeType: String?): Boolean {
        return DecodeUtils.isGif(source) || DecodeUtils.isAnimatedWebP(source)
    }

    override suspend fun decode(
        pool: BitmapPool,
        source: BufferedSource,
        size: Size,
        options: Options
    ): DecodeResult {
        val tempFile = createTempFile()

        try {
            var isSampled = false

            // Work around https://issuetracker.google.com/issues/139371066 by copying the source to a temp file.
            source.use { tempFile.sink().use { source.readAll(it) } }
            val decoderSource = ImageDecoder.createSource(tempFile)

            val baseDrawable = decoderSource.decodeDrawable { info, _ ->
                // It's safe to delete the temp file here.
                tempFile.delete()

                if (size is PixelSize) {
                    val (srcWidth, srcHeight) = info.size
                    val multiplier = DecodeUtils.computeSizeMultiplier(
                        srcWidth = srcWidth,
                        srcHeight = srcHeight,
                        destWidth = size.width,
                        destHeight = size.height,
                        scale = options.scale
                    )

                    // Set the target size if the image is larger than the requested dimensions
                    // or the request requires exact dimensions.
                    isSampled = multiplier < 1
                    if (isSampled || !options.allowInexactSize) {
                        val targetWidth = (multiplier * srcWidth).roundToInt()
                        val targetHeight = (multiplier * srcHeight).roundToInt()
                        setTargetSize(targetWidth, targetHeight)
                    }
                }

                if (options.config != Bitmap.Config.HARDWARE) {
                    allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }

                if (options.colorSpace != null) {
                    setTargetColorSpace(options.colorSpace)
                }

                memorySizePolicy = if (options.allowRgb565) {
                    ImageDecoder.MEMORY_POLICY_LOW_RAM
                } else {
                    ImageDecoder.MEMORY_POLICY_DEFAULT
                }
            }

            val drawable = if (baseDrawable is AnimatedImageDrawable) {
                baseDrawable.repeatCount = options.parameters.repeatCount() ?: AnimatedImageDrawable.REPEAT_INFINITE

                // Wrap AnimatedImageDrawable in a ScaleDrawable so it always scales to fill its bounds.
                ScaleDrawable(baseDrawable, options.scale)
            } else {
                baseDrawable
            }

            return DecodeResult(
                drawable = drawable,
                isSampled = isSampled
            )
        } finally {
            tempFile.delete()
        }
    }
}
