package app.family.notifyer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference

/**
 * The ringing screen of an incoming call on a locked (or dark) phone - the
 * phone's own incoming-call screen, not the app: who is calling, Decline and
 * Answer. Only Answer opens the app. The ringtone is the call notification's
 * own (Notes.ring), so the phone's ringtone, ring volume and Do Not Disturb
 * apply; this screen plays nothing itself.
 */
class IncomingCallActivity : AppCompatActivity() {
    companion object {
        private var current: WeakReference<IncomingCallActivity>? = null

        fun intent(c: Context, tag: String, kind: String, who: String, title: String,
                   from: String?, answer: String): Intent =
            Intent(c, IncomingCallActivity::class.java)
                .setAction("app.family.notifyer.ring.$tag")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("tag", tag).putExtra("kind", kind).putExtra("who", who)
                .putExtra("title", title).putExtra("from", from).putExtra("answer", answer)

        /** The call stopped ringing (answered, declined, hung up, missed): the screen goes. */
        fun close(tag: String?) {
            val a = current?.get() ?: return
            a.runOnUiThread { if (tag == null || tag == a.tag) a.finish() }
        }
    }

    private var tag = ""
    private val timeout = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overLock()
        if (!show(intent)) { finish(); return }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            /* Away, like the phone's own call screen: the call goes on ringing. */
            override fun handleOnBackPressed() = finish()
        })
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (!show(i)) finish()
    }

    override fun onDestroy() {
        timeout.removeCallbacksAndMessages(null)
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    /** The volume keys quieten the ring, as on a phone call; the screen stays. */
    override fun onKeyDown(code: Int, e: KeyEvent?): Boolean {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) {
            Notes.quietRing(this, tag)
            return true
        }
        return super.onKeyDown(code, e)
    }

    @Suppress("DEPRECATION")
    private fun overLock() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Draws the call in i; false when there is nothing (left) to ring. */
    private fun show(i: Intent): Boolean {
        val t = i.getStringExtra("tag") ?: return false
        /* Answered or declined while the screen was on its way. */
        if (Store.isSilenced(this, t) || !Notes.isRinging(this, t)) return false
        tag = t
        current = WeakReference(this)
        val kind = i.getStringExtra("kind") ?: "call"
        val who = i.getStringExtra("who") ?: getString(R.string.app_name)
        val answer = i.getStringExtra("answer") ?: ""
        setContentView(layout(who, i.getStringExtra("title") ?: "", i.getStringExtra("from"),
            onDecline = {
                NoteActionReceiver.decline(this, t)
                finish()
            },
            onAnswer = {
                startActivity(MainActivity.intent(this, answer, t, kind, MainActivity.HOW_ANSWER))
                finish()
            }))
        /* As long as the notification rings (Notes.ring's setTimeoutAfter), not longer. */
        timeout.removeCallbacksAndMessages(null)
        timeout.postDelayed({ finish() }, 65_000)
        return true
    }

    /* ---------------------------------------------------------------- the screen */

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun layout(who: String, title: String, from: String?, onDecline: () -> Unit, onAnswer: () -> Unit): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(getColor(R.color.night))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(72), dp(24), 0)
        }
        val pic = Notes.photo(this, from)
        val face: View = if (pic != null) ImageView(this).apply { setImageBitmap(pic) }
        else TextView(this).apply {
            text = who.trim().take(1).uppercase()
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.night))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.brass)) }
        }
        top.addView(face, LinearLayout.LayoutParams(dp(120), dp(120)))
        top.addView(TextView(this).apply {
            text = who
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setPadding(0, dp(24), 0, dp(8))
        })
        top.addView(TextView(this).apply {
            text = Notes.tr(this@IncomingCallActivity, title.ifBlank { "Incoming call" })
            gravity = Gravity.CENTER
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(40), 0, dp(40), dp(64))
        }
        buttons.addView(button(R.string.call_decline, 0xFFE5484D.toInt(), android.R.drawable.ic_menu_close_clear_cancel, onDecline),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(button(R.string.call_answer, 0xFF30A46C.toInt(), android.R.drawable.sym_action_call, onAnswer),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    /** A round coloured button with its word under it. */
    private fun button(label: Int, colour: Int, icon: Int, onTap: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val round = ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colour) }
            contentDescription = getString(label)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
        }
        box.addView(round, LinearLayout.LayoutParams(dp(76), dp(76)))
        box.addView(TextView(this).apply {
            setText(label)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(10), 0, 0)
        })
        return box
    }
}
