package dev.mrwick.redline.nav

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import kotlinx.coroutines.flow.first
import android.widget.TextView
import dev.mrwick.redline.protocol.SuzukiProfile
import dev.mrwick.redline.util.AppLog
import java.util.concurrent.atomic.AtomicLong

/**
 * Parses Google Maps turn-by-turn notifications into [ParsedNavData].
 *
 * The notification provides live route text, remaining distance and ETA.
 * Maneuver IDs inferred from Google Maps notification text/icons are heuristic;
 * Suzuki's cluster-byte translation is applied after that classification. Verify
 * arrows on the target cluster during a stationary route test before relying on
 * them while riding.
 *
 * 2026-05-25 empirical finding (see captures/maps-notification-dump-*.md): current Maps
 * versions on Android 16 use the standard `Notification.extras` ProgressStyle template,
 * NOT a custom RemoteViews layout as the 2020 reference implementation assumed.
 *
 * Layout we observe in extras:
 *   android.title       → "Head toward MG Road" / "In 200 m turn left" — the instruction
 *   android.subText     → "Arrive 8:14 am" / "5 min · 1.2 km" — ETA + maybe total dist
 *   android.progress    → route progress (2026-10-20 capture interpretation)
 *   android.progressMax → route extent, unchanged across multiple turns
 *   android.largeIcon   → the turn-arrow Bitmap (132×132 typical)
 *
 * Strategy:
 *  1. Try extras path first (handles current Maps versions cleanly)
 *  2. Fall back to RemoteViews-walk for older Maps that still ships nav_title etc.
 *  3. Treat lifecycle notifications separately; never substitute route distance
 *     for a missing next-turn distance.
 */
object GoogleMapsParser {
    private val traceSequence = AtomicLong()

    /** Google Maps' Play Store / system package name. */
    const val PKG_GOOGLE_MAPS = "com.google.android.apps.maps"

    /**
     * Parse [sbn] as a Google Maps nav notification. Returns `null` when the
     * SBN is not from Maps or no nav data could be recovered.
     */
    fun parse(context: Context, sbn: StatusBarNotification): ParsedNavData? {
        if (sbn.packageName != PKG_GOOGLE_MAPS) return null
        val notification = sbn.notification ?: return null
        val receivedElapsedMs = System.nanoTime() / 1_000_000
        val traceId = "maps-${System.currentTimeMillis()}-${traceSequence.incrementAndGet()}"
        val extras = notification.extras
        AppLog.i(
            "NavTrace",
            "INPUT direction=MAPS_TO_APP source=ANDROID_NOTIFICATION trace=$traceId build=${dev.mrwick.redline.BuildConfig.SOURCE_FINGERPRINT.take(16)} keyHash=${sbn.key.hashCode()} postedMs=${sbn.postTime} " +
                "receivedMs=${System.currentTimeMillis()} postAgeMs=${System.currentTimeMillis() - sbn.postTime} " +
                "notificationWhen=${notification.`when`} flags=0x${notification.flags.toString(16)} " +
                "interactive=${context.getSystemService(android.os.PowerManager::class.java).isInteractive} " +
                "powerSave=${context.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode} " +
                "title=${NavDiagnostics.quoted(extras?.getCharSequence(Notification.EXTRA_TITLE))} " +
                "text=${NavDiagnostics.quoted(extras?.getCharSequence(Notification.EXTRA_TEXT))} " +
                "subText=${NavDiagnostics.quoted(extras?.getCharSequence(Notification.EXTRA_SUB_TEXT))} " +
                "bigText=${NavDiagnostics.quoted(extras?.getCharSequence(Notification.EXTRA_BIG_TEXT))} " +
                "summary=${NavDiagnostics.quoted(extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))} " +
                "textLines=${extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(prefix = "[", postfix = "]") { NavDiagnostics.quoted(it) }} " +
                "progress=${extras?.getInt(Notification.EXTRA_PROGRESS, -1)} " +
                "progressMax=${extras?.getInt(Notification.EXTRA_PROGRESS_MAX, -1)} " +
                "indeterminate=${extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)} " +
                "template=${NavDiagnostics.quoted(extras?.getString(Notification.EXTRA_TEMPLATE))}",
        )
        val profile = kotlinx.coroutines.runBlocking {
            dev.mrwick.redline.app.AppGraph.settings(context).pairedSuzuki.first().profile
        }

        // Primary path: extras (current Maps versions).
        val parsed = parseFromExtras(context, notification, profile, traceId)
            ?: parseFromRemoteViews(context, notification, profile, traceId)
        if (parsed == null) {
            AppLog.w("NavTrace", "REJECT trace=$traceId neither extras nor RemoteViews produced navigation data")
            return null
        }
        val traced = parsed.copy(traceId = traceId, sourceElapsedMs = receivedElapsedMs)
        AppLog.i(
            "NavTrace",
            "PARSED source=APP_PARSER ${NavDiagnostics.fields(traced.toNavFrame())} " +
                "instruction=${NavDiagnostics.quoted(traced.streetName)} profile=${profile.description} " +
                "parseMs=${System.nanoTime() / 1_000_000 - receivedElapsedMs}",
        )
        return traced
    }

    // ----------------------------------------------------------------------
    // Primary path: Notification.extras (Maps ProgressStyle template)
    // ----------------------------------------------------------------------

    private fun parseFromExtras(context: Context, n: Notification, profile: SuzukiProfile, traceId: String): ParsedNavData? {
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        if (title.isNullOrBlank() && subText.isNullOrBlank() && text.isNullOrBlank()) return null

        val content = MapsNotificationContent(
            title, text, subText,
            extras.getInt(Notification.EXTRA_PROGRESS, -1),
            extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1),
            extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            extras.getString(Notification.EXTRA_TEMPLATE) != null,
        )
        val instruction = content.instruction

        // Maneuver bitmap: largeIcon on this notification is the turn arrow.
        val maneuverBitmap = extractLargeIconBitmap(context, n)
        // Read self-train pref synchronously via runBlocking on the DataStore flow.
        // Cheap (one read per Maps notification, ~2 Hz max); avoids threading the
        // pref through the whole parse API.
        val selfTrain = kotlinx.coroutines.runBlocking {
            dev.mrwick.redline.app.AppGraph.settings(context).maneuverSelfTrainEnabled.first()
        }
        val classification = ManeuverClassifier.classifyDetailed(maneuverBitmap, instruction, selfTrain)
        val parsed = content.parse(profile, classification.id)
        AppLog.i(
            "NavTrace",
            "DECISION trace=$traceId path=extras state=${parsed.navigationState} " +
                "headingOnly=${content.isHeadingOnly} arrowPolicy=${if (content.isHeadingOnly) "explicit-compass-only-NOT-relative-turn" else "maneuver-or-lifecycle"} " +
                "roundaboutExit=${parsed.roundaboutExit} exitAngle=unavailable geometry=unavailable " +
                "nextSource=${if (parsed.nextDistanceKnown) "instruction-prefix" else "unavailable-NOT-route-progress"} " +
                "totalSource=${parsed.totalDistanceSource} routeRemainingMetres=${parsed.routeRemainingMetres} " +
                "routeTravelledMetres=${parsed.routeTravelledMetres} etaSource=${etaInputSource(subText)} " +
                "mapplsId=${classification.id} classifier=${classification.source} textId=${classification.textId} " +
                "hashId=${classification.hashId} iconHash=${classification.hash?.let { java.lang.Long.toHexString(it) }} " +
                "iconSize=${maneuverBitmap?.width}x${maneuverBitmap?.height} selfTrain=$selfTrain",
        )
        dev.mrwick.redline.util.AppLog.i(
            "MapsParser",
            "notif title=\"${title?.take(60)}\" bitmap=${maneuverBitmap != null} -> mapplsId=${classification.id ?: "unclassified"} clusterByte=${parsed.maneuverId} state=${parsed.navigationState} profile=${profile.description}",
        )

        return parsed
    }

    /** Pull the Bitmap out of Notification.largeIcon (when present + bitmap-backed). */
    private fun extractLargeIconBitmap(context: Context, n: Notification): Bitmap? {
        val icon: Icon = n.getLargeIcon() ?: return null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            (drawable as? BitmapDrawable)?.bitmap
        } catch (_: Throwable) {
            null
        }
    }

    // ----------------------------------------------------------------------
    // Fallback path: RemoteViews tree walk (older Maps versions, 2020 GMapsParser)
    // ----------------------------------------------------------------------

    private fun parseFromRemoteViews(context: Context, notification: Notification, profile: SuzukiProfile, traceId: String): ParsedNavData? {
        val bigViews = try {
            Notification.Builder.recoverBuilder(context, notification).createBigContentView()
        } catch (_: Throwable) { null }
            ?: notification.bigContentView
            ?: notification.contentView
            ?: return null

        val inflaterCtx = try {
            context.createPackageContext(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "com.android.systemui"
                else PKG_GOOGLE_MAPS,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        } catch (_: Throwable) {
            context
        }

        val root: View = try {
            bigViews.apply(inflaterCtx, null)
        } catch (_: Throwable) {
            return null
        }

        val titles = mutableMapOf<String, String>()
        val maneuverBitmap = walkAndCollect(root, titles)

        val distRaw = titles["nav_title"] ?: titles["lockscreen_directions"]
        val instruction = titles["nav_description"] ?: titles["lockscreen_oneliner"]
        val timeRaw = titles["nav_time"] ?: titles["lockscreen_eta"]

        if (distRaw.isNullOrBlank() && instruction.isNullOrBlank()) return null

        val classification = ManeuverClassifier.classifyDetailed(maneuverBitmap, instruction, selfTrainEnabled = false)
        val parsed = MapsNotificationContent(distRaw, instruction, timeRaw).parse(profile, classification.id)
        AppLog.i(
            "NavTrace",
            "DECISION trace=$traceId path=remoteViews nextRaw=${NavDiagnostics.quoted(distRaw)} " +
                "roundaboutExit=${parsed.roundaboutExit} exitAngle=unavailable geometry=unavailable " +
                "timeRaw=${NavDiagnostics.quoted(timeRaw)} totalSource=${parsed.totalDistanceSource} state=${parsed.navigationState} etaSource=${etaInputSource(timeRaw)} " +
                "mapplsId=${classification.id} classifier=${classification.source} textId=${classification.textId} " +
                "hashId=${classification.hashId} iconHash=${classification.hash?.let { java.lang.Long.toHexString(it) }} " +
                "iconSize=${maneuverBitmap?.width}x${maneuverBitmap?.height} selfTrain=false",
        )
        return parsed
    }

    /** RemoteViews tree walker (fallback path). */
    private fun walkAndCollect(view: View, into: MutableMap<String, String>): Bitmap? {
        var bitmap: Bitmap? = null
        if (view is TextView) {
            val name = try {
                view.resources.getResourceEntryName(view.id)
            } catch (_: Throwable) { null }
            if (name != null && view.text != null) into[name] = view.text.toString()
        }
        if (view is ImageView) {
            val name = try {
                view.resources.getResourceEntryName(view.id)
            } catch (_: Throwable) { null }
            if (name == "nav_notification_icon" ||
                name == "right_icon" ||
                name == "lockscreen_notification_icon"
            ) {
                val drawable = view.drawable
                if (drawable is BitmapDrawable) bitmap = drawable.bitmap
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkAndCollect(view.getChildAt(i), into)?.let { bitmap = it }
            }
        }
        return bitmap
    }

}
