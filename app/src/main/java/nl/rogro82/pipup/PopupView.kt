package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

// TODO: convert dimensions from px to dp

@SuppressLint("ViewConstructor")
sealed class PopupView(context: Context, val popup: PopupProps) : LinearLayout(context) {

    open fun create() {
        inflate(context, R.layout.popup,this)

        layoutParams = LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            orientation = VERTICAL
            minimumWidth = 240
        }

        setPadding(20,20,20,20)

        val title = findViewById<TextView>(R.id.popup_title)
        val message = findViewById<TextView>(R.id.popup_message)
        val frame = findViewById<FrameLayout>(R.id.popup_frame)

        if(popup.media == null) {
            removeView(frame)
        }

        if(popup.title.isNullOrEmpty()) {
            removeView(title)
        } else {
            title.text = popup.title
            title.textSize = popup.titleSize
            title.setTextColor(Color.parseColor(popup.titleColor))
        }

        if(popup.message.isNullOrEmpty()) {
            removeView(message)
        } else {
            message.text = popup.message
            message.textSize = popup.messageSize
            message.setTextColor(Color.parseColor(popup.messageColor))
        }

        setBackgroundColor(Color.parseColor(popup.backgroundColor))
    }

    open fun destroy() {}

    private class Default(context: Context, popup: PopupProps) : PopupView(context, popup) {
        init { create() }
    }

    private class Video(context: Context, popup: PopupProps, val media: PopupProps.Media.Video): PopupView(context, popup) {
        // Media3 ExoPlayer instead of VideoView: plays RTSP camera streams (VideoView cannot), and shows the
        // popup straight away with a black frame while a slow stream (a Ring doorbell takes ~9 s) connects.
        private var mPlayer: ExoPlayer? = null
        private var mFirstFrame = false
        private val mRetryHandler = android.os.Handler(android.os.Looper.getMainLooper())

        init { create() }

        @OptIn(UnstableApi::class)
        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            // TextureView, not PlayerView's default SurfaceView: a SurfaceView sits behind its window, and inside
            // this overlay popup that leaves a black box over the video.
            val textureView = TextureView(context)
            frame.setBackgroundColor(Color.BLACK)
            frame.addView(textureView, FrameLayout.LayoutParams(media.width, media.width * 9 / 16).apply {
                gravity = Gravity.CENTER
            })

            // Optional poster: a recent still from the same camera, shown until the first video frame arrives
            // (a new RTSP viewer waits up to a few seconds for the camera's next key frame).
            val posterView = media.poster?.let { poster ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    frame.addView(this, FrameLayout.LayoutParams(media.width, media.width * 9 / 16).apply {
                        gravity = Gravity.CENTER
                    })
                    Glide.with(context)
                        .load(Uri.parse(poster))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .into(this)
                }
            }

            val uri = Uri.parse(media.uri)
            val item = MediaItem.fromUri(uri)
            // Start playing as soon as a quarter of a second is buffered instead of ExoPlayer's default
            // 2.5 s, and skip the audio track (muted anyway): a doorbell should appear as fast as possible.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 2000, 250, 500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            mPlayer = ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                if (uri.scheme.equals("rtsp", ignoreCase = true)) {
                    setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(item))
                } else {
                    setMediaItem(item)
                }
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        mFirstFrame = true
                        Log.i("PiPupVideo", "first frame rendered")
                        posterView?.visibility = View.GONE
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PiPupVideo", "playback error: ${error.errorCodeName} ${PiPupService.redact(error.message)}")
                    }
                })
                playWhenReady = true
                prepare()
            }
            mPlayer?.setVideoTextureView(textureView)

            // A live RTSP join occasionally stalls without an error (no frames ever arrive). If nothing has been
            // shown after 5 s, reconnect; repeat a few times while the popup is up.
            fun scheduleRetry(attempt: Int) {
                mRetryHandler.postDelayed({
                    val player = mPlayer ?: return@postDelayed
                    if (mFirstFrame || attempt > 3) return@postDelayed
                    Log.w("PiPupVideo", "no video after join, reconnecting (attempt $attempt)")
                    player.stop()
                    if (uri.scheme.equals("rtsp", ignoreCase = true)) {
                        player.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(item))
                    } else {
                        player.setMediaItem(item)
                    }
                    player.prepare()
                    scheduleRetry(attempt + 1)
                // A cold Ring camera can take ~11 s to send its first frame; reconnecting sooner kills that start
                }, if (attempt == 1) 15000L else 8000L)
            }
            scheduleRetry(1)
        }

        override fun destroy() {
            mRetryHandler.removeCallbacksAndMessages(null)
            try {
                mPlayer?.release()
            } catch(e: Throwable) {}
            mPlayer = null
        }
    }

    private class Image(context: Context, popup: PopupProps, val media: PopupProps.Media.Image): PopupView(context, popup) {
        init { create() }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)

            try {
                val imageView = ImageView(context)

                val layoutParams =
                    FrameLayout.LayoutParams(media.width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }

                frame.addView(imageView, layoutParams)

                Glide.with(context)
                    .load(Uri.parse(media.uri))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .into(imageView)

            } catch(e: Throwable) {
                removeView(frame)
            }
        }
    }

    private class Bitmap(context: Context, popup: PopupProps, val media: PopupProps.Media.Bitmap): PopupView(context, popup) {
        var mImageView: ImageView? = null

        init { create() }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            mImageView = ImageView(context).apply {
                setImageBitmap(media.image)
            }

            val scaledHeight = ((media.width.toFloat() / media.image.width) * media.image.height).toInt()
            val layoutParams =
                FrameLayout.LayoutParams(media.width, scaledHeight).apply {
                    gravity = Gravity.CENTER
                }

            frame.addView(mImageView, layoutParams)
        }

        override fun destroy() {
            try {
                mImageView?.setImageDrawable(null)
                media.image.recycle()
            } catch(e: Throwable) {}
        }
    }

    private class Web(context: Context, popup: PopupProps, val media: PopupProps.Media.Web): PopupView(context, popup) {
        init { create() }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            val webView = WebView(context).apply {
                with(settings) {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                loadUrl(media.uri)
            }

            val layoutParams = FrameLayout.LayoutParams(
                media.width,
                media.height
            ).apply {
                gravity = Gravity.CENTER
            }

            frame.addView(webView, layoutParams)
        }
    }

    companion object {
        const val LOG_TAG = "PopupView"

        fun build(context: Context, popup: PopupProps): PopupView
        {
            return when (popup.media) {
                is PopupProps.Media.Web -> Web(context, popup, popup.media)
                is PopupProps.Media.Video -> Video(context, popup, popup.media)
                is PopupProps.Media.Image -> Image(context, popup, popup.media)
                is PopupProps.Media.Bitmap -> Bitmap(context, popup, popup.media)
                else -> Default(context, popup)
            }
        }
    }
}