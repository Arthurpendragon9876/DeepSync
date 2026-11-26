-optimizationpasses 8
-dontobfuscate

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn coil3.PlatformContext

-keep class org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment

-keep class org.koitharu.kotatsu.core.exceptions.* { *; }
-keep class org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy { *; }
-keep class org.jsoup.parser.Tag
-keep class org.jsoup.internal.StringUtil

-keep class org.acra.security.NoKeyStoreFactory { *; }
-keep class org.acra.config.DefaultRetryPolicy { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class org.acra.sender.JobSenderService

# Preference XML and FragmentManager may instantiate fragments by class name.
-keep class org.koitharu.kotatsu.**Fragment { *; }

# ============================================================
# Shizuku user service
# Instantiated via Class.newInstance() by Shizuku's ServiceStarter
# in a separate process; the app never references its constructor
# or methods directly, so R8 would otherwise strip them and the
# service process dies with InstantiationException.
# ============================================================
-keep class org.koitharu.kotatsu.extensions.install.shizuku.** { *; }

# ============================================================
# Mihon Extension Support
# Extensions are separate APKs loaded at runtime via
# ChildFirstPathClassLoader. They depend on these host classes.
# ============================================================

# Keep attributes needed for reflection and serialization
-keepattributes Signature
-keepattributes Annotation
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# Tachiyomi / Mihon API classes
-keep class eu.kanade.tachiyomi.** { *; }
-keep interface eu.kanade.tachiyomi.** { *; }
-keepclassmembers class eu.kanade.tachiyomi.** {
    public <init>(...);
    public protected *;
}
-keep class tachiyomi.core.common.** { *; }
-keep interface tachiyomi.core.common.** { *; }
-keepclassmembers class tachiyomi.core.common.** {
    public <init>(...);
    public protected *;
}

# Injekt dependency injection (used by extensions via injectLazy)
-keep class uy.kohesive.injekt.** { *; }
-keep interface uy.kohesive.injekt.** { *; }
-keepclassmembers class uy.kohesive.injekt.** {
    public <init>(...);
    public protected *;
}

# Injekt's Injekt.get<T>() / injectLazy<T>() compile to anonymous subclasses of TypeReference<T> and read
# their generic superclass at runtime. R8 full mode strips that Signature from the (unreferenced) anonymous
# subclasses, which fails in release with "TypeReference constructed without actual type information".
-keep class uy.kohesive.injekt.api.TypeReference { *; }
-keep class * extends uy.kohesive.injekt.api.TypeReference { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod

# RxJava (used by legacy extension API)
-keep class rx.** { *; }
-keep interface rx.** { *; }
-dontwarn rx.**

# QuickJS (used by Mihon's JavaScriptEngine and directly by some extensions)
-keep class app.cash.quickjs.** { *; }
-keepclassmembers class app.cash.quickjs.** {
    public <init>(...);
    public protected *;
}

# OkHttp and Okio (used by extensions)
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepclassmembers class okhttp3.** {
    public <init>(...);
}
-keep class okio.** { *; }
-keep interface okio.** { *; }
-dontwarn okio.**
-dontwarn okhttp3.**

# zstd JNI (okhttp3.zstd CompressionInterceptor) — native code resolves
# ZstdCompressor/ZstdDecompressor via JNI FindClass, invisible to R8.
-keep class com.squareup.zstd.** { *; }

# UniFile (used by Mihon DiskUtil signatures)
-keep class com.hippo.unifile.** { *; }
-keep interface com.hippo.unifile.** { *; }
-dontwarn com.hippo.unifile.**

# Mihon bridge and model classes
-keep class org.koitharu.kotatsu.mihon.** { *; }
-keepclassmembers class org.koitharu.kotatsu.mihon.** {
    public <init>(...);
    public protected *;
}
-keep class org.koitharu.kotatsu.mihon.compat.** { *; }

# Jsoup (used by ParsedHttpSource)
-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.** {
    public <init>(...);
}
-dontwarn com.google.re2j.**

# kotlinx.serialization (used by some extensions)
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keepclassmembers class **$$serializer {
    *** INSTANCE;
}

# xmlutil (nl.adaptivity.xmlutil) — used by extensions that parse XML manga sites
-keep class nl.adaptivity.xmlutil.** { *; }
-keep interface nl.adaptivity.xmlutil.** { *; }
-keepclassmembers class nl.adaptivity.xmlutil.** { *; }
-keep public enum nl.adaptivity.xmlutil.EventType { *; }
-dontwarn nl.adaptivity.xmlutil.**

# Dalvik ClassLoader (used by ChildFirstPathClassLoader)
-keep class dalvik.system.** { *; }
-dontwarn dalvik.system.**

# Application class (Injekt injects Application instances)
-keep class android.app.Application { *; }
-keepclassmembers class * extends android.app.Application {
    public <init>(...);
}

# SharedPreferences (ConfigurableSource uses it)
-keep class android.content.SharedPreferences { *; }
-keep interface android.content.SharedPreferences$** { *; }

# Kotlin stdlib
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-dontwarn kotlin.**

# Kotlin LazyKt
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__LazyJVMKt { *; }
-keep class kotlin.LazyKt__LazyKt { *; }
-keep class kotlin.SynchronizedLazyImpl { *; }
-keep class kotlin.UnsafeLazyImpl { *; }

# Kotlin reflection
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# Kotlin coroutines (extensions-lib 1.5+)
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# AndroidX Preference (ConfigurableSource settings screen)
-keep class androidx.preference.** { *; }
-keep interface androidx.preference.** { *; }
-keepclassmembers class androidx.preference.** {
    public <init>(...);
    public protected *;
}

# Preserve AppCompat optional menu icon method name for release reflection fallback.
-keepclassmembers class androidx.appcompat.view.menu.** {
    void setOptionalIconsVisible(boolean);
}

# ---- Cloudstream (.cs3) plugin support ----
# NewPipeExtractor (issue #608)
# YouTube cs3 plugins link straight against it, as the official Cloudstream app bundles it, and subclass its
# extractors (`object : YoutubeStreamExtractor(...)`), so its whole API stays as published. nanojson is its JSON layer.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep interface org.schabi.newpipe.extractor.** { *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn com.grack.nanojson.**

# Cloudstream runtime API
# Cloudstream cs3 plugins are loaded dynamically via DexClassLoader and depend on
# the host app's bundled runtime packages. Even with -dontobfuscate enabled,
# R8 can still strip classes/members not referenced directly by the host app.
# Keep only the runtime surface used by plugins and the host integration, not the full Cloudstream app shell.
-keep class com.lagradost.cloudstream3.** { *; }
-keep interface com.lagradost.cloudstream3.** { *; }
-keep class com.lagradost.cloudstream3.MainAPI { *; }
-keep class com.lagradost.cloudstream3.MainAPI$Companion { *; }
-keep class com.lagradost.cloudstream3.MainAPIKt { *; }
-keep class com.lagradost.cloudstream3.MainActivityKt { *; }
-keep class com.lagradost.cloudstream3.APIHolder { *; }
-keep class com.lagradost.cloudstream3.Prerelease { *; }
-keep class com.lagradost.cloudstream3.ProviderType { *; }
-keep class com.lagradost.cloudstream3.VPNStatus { *; }
-keep class com.lagradost.cloudstream3.TvType { *; }
-keep class com.lagradost.cloudstream3.DubStatus { *; }
-keep class com.lagradost.cloudstream3.Score { *; }
-keep class com.lagradost.cloudstream3.Tracker { *; }
-keep class com.lagradost.cloudstream3.TrackerType { *; }
-keep class com.lagradost.cloudstream3.ProvidersInfoJson { *; }
-keep class com.lagradost.cloudstream3.SettingsJson { *; }
-keep class com.lagradost.cloudstream3.MainPageData { *; }
-keep class com.lagradost.cloudstream3.MainPageRequest { *; }
-keep class com.lagradost.cloudstream3.HomePageList { *; }
-keep class com.lagradost.cloudstream3.HomePageResponse { *; }
-keep class com.lagradost.cloudstream3.SearchResponse { *; }
-keep class com.lagradost.cloudstream3.SearchResponseList { *; }
-keep class com.lagradost.cloudstream3.SearchQuality { *; }
-keep class com.lagradost.cloudstream3.MovieSearchResponse { *; }
-keep class com.lagradost.cloudstream3.TvSeriesSearchResponse { *; }
-keep class com.lagradost.cloudstream3.AnimeSearchResponse { *; }
-keep class com.lagradost.cloudstream3.LiveSearchResponse { *; }
-keep class com.lagradost.cloudstream3.LoadResponse { *; }
-keep class com.lagradost.cloudstream3.MovieLoadResponse { *; }
-keep class com.lagradost.cloudstream3.TvSeriesLoadResponse { *; }
-keep class com.lagradost.cloudstream3.AnimeLoadResponse { *; }
-keep class com.lagradost.cloudstream3.LiveStreamLoadResponse { *; }
-keep class com.lagradost.cloudstream3.TorrentLoadResponse { *; }
-keep class com.lagradost.cloudstream3.TorrentSearchResponse { *; }
-keep class com.lagradost.cloudstream3.Episode { *; }
-keep class com.lagradost.cloudstream3.EpisodeResponse { *; }
-keep class com.lagradost.cloudstream3.SeasonData { *; }
-keep class com.lagradost.cloudstream3.SubtitleFile { *; }
-keep class com.lagradost.cloudstream3.AudioFile { *; }
-keep class com.lagradost.cloudstream3.Actor { *; }
-keep class com.lagradost.cloudstream3.ActorData { *; }
-keep class com.lagradost.cloudstream3.ActorRole { *; }
-keep class com.lagradost.cloudstream3.ErrorLoadingException { *; }
-keep class com.lagradost.cloudstream3.syncproviders.SyncIdName { *; }
-keep class com.lagradost.cloudstream3.plugins.BasePlugin { *; }
-keep class com.lagradost.cloudstream3.plugins.BasePluginKt { *; }
-keep class com.lagradost.cloudstream3.plugins.Plugin { *; }
-keep class com.lagradost.cloudstream3.plugins.CloudstreamPlugin { *; }
-keep class com.lagradost.cloudstream3.plugins.PluginData { *; }
-keep class com.lagradost.cloudstream3.plugins.SitePlugin { *; }
-keep class com.lagradost.cloudstream3.utils.ExtractorApi { *; }
-keep class com.lagradost.cloudstream3.utils.ExtractorApiKt { *; }
-keep class com.lagradost.cloudstream3.utils.ExtractorLink { *; }
-keep class com.lagradost.cloudstream3.utils.DrmExtractorLink { *; }
-keep class com.lagradost.cloudstream3.utils.ExtractorLinkPlayList { *; }
-keep class com.lagradost.cloudstream3.utils.ExtractorLinkType { *; }
-keep class com.lagradost.cloudstream3.utils.DataStore { *; }
-keep class com.lagradost.cloudstream3.utils.DataStoreKt { *; }
-keep class com.lagradost.cloudstream3.utils.Editor { *; }
-keep class com.lagradost.cloudstream3.utils.PreferenceDelegate { *; }
-keep class com.lagradost.cloudstream3.utils.HlsPlaylistParser { *; }
-keep class com.lagradost.cloudstream3.utils.M3u8Helper { *; }
-keep class com.lagradost.cloudstream3.utils.M3u8Helper2 { *; }
-keep class com.lagradost.cloudstream3.utils.Qualities { *; }
-keep class com.lagradost.cloudstream3.utils.ShortLink { *; }
-keep class com.lagradost.cloudstream3.utils.SubtitleHelper { *; }
-keep class com.lagradost.cloudstream3.utils.SubtitleHelperKt { *; }
-keep class com.lagradost.cloudstream3.utils.SubtitleUtils { *; }
-keep class com.lagradost.cloudstream3.utils.AppUtils { *; }
-keep class com.lagradost.cloudstream3.utils.Coroutines { *; }
-keep class com.lagradost.cloudstream3.utils.Coroutines_jvmKt { *; }
-keep class com.lagradost.cloudstream3.utils.StringUtils { *; }
-keep class com.lagradost.cloudstream3.network.** { *; }
-keep class com.lagradost.cloudstream3.extractors.** { *; }
-keepclassmembers class com.lagradost.cloudstream3.MainAPI {
    public <init>(...);
    public protected *;
}
-keepclassmembers class com.lagradost.cloudstream3.plugins.BasePlugin {
    public <init>(...);
    public protected *;
}
-keepclassmembers class com.lagradost.cloudstream3.plugins.Plugin {
    public <init>(...);
    public protected *;
}
-keepclassmembers class com.lagradost.cloudstream3.utils.ExtractorApi {
    public <init>(...);
    public protected *;
}
-keepclassmembers class com.lagradost.cloudstream3.extractors.** {
    public <init>(...);
    public protected *;
}

-keep class com.lagradost.api.** { *; }
-keep interface com.lagradost.api.** { *; }
-keepclassmembers class com.lagradost.api.** {
    public <init>(...);
    public protected *;
}

-keep class com.lagradost.nicehttp.** { *; }
-keep interface com.lagradost.nicehttp.** { *; }
-keepclassmembers class com.lagradost.nicehttp.** {
    public <init>(...);
    public protected *;
}

# Cloudstream extra dependencies used at runtime by the bundled prerelease jar
# and some cs3 plugins.
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

-keep class me.xdrop.fuzzywuzzy.** { *; }
-keep class me.xdrop.diffutils.** { *; }
-dontwarn me.xdrop.fuzzywuzzy.**
-dontwarn me.xdrop.diffutils.**

# Rhino (Cloudstream JsInterpreter, NewPipeExtractor)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**

# Cloudstream's bundled jar references Ksoup (HTML parser), which Yugen doesn't ship.
# Only used by extractors like SBPlay; safe to ignore at shrink time.
-dontwarn com.fleeksoft.ksoup.**
