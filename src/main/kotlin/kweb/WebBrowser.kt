package kweb

import io.mola.galimatias.URL
import kweb.client.HttpRequestInfo
import kweb.client.Server2ClientMessage.Instruction
import kweb.html.Document
import kweb.plugins.KwebPlugin
import kweb.state.*
import mu.KotlinLogging
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.*
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

/**
 * A conduit for communicating with a remote web browser, can be used to execute JavaScript and evaluate JavaScript
 * expressions and retrieveJs the result.
 */

val logger = KotlinLogging.logger {}

class WebBrowser(private val sessionId: String, val httpRequestInfo: HttpRequestInfo, internal val kweb: Kweb) {

    private val idCounter = AtomicInteger(0)

    /**
     * During page render, the initial HTML document will be available for modification as a
     * [JSoup Document](https://jsoup.org/) in this [AtomicReference].
     *
     * Callers to [execute] may check for this being non-null, and if so edit the document
     * *instead* of some or all of the JavaScript they must call.
     *
     * The purpose of this is to implement Server-Side Rendering.
     */
    val htmlDocument = AtomicReference<org.jsoup.nodes.Document?>(null)

    fun generateId(): String = idCounter.getAndIncrement().toString(36)

    private val plugins: Map<KClass<out KwebPlugin>, KwebPlugin> by lazy {
        kweb.appliedPlugins.map { it::class to it }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <P : KwebPlugin> plugin(plugin: KClass<out P>): P {
        return (plugins[plugin] ?: error("Plugin $plugin is missing")) as P
    }

    internal fun require(vararg requiredPlugins: KClass<out KwebPlugin>) {
        val missing = java.util.HashSet<String>()
        for (requiredPlugin in requiredPlugins) {
            if (!plugins.contains(requiredPlugin)) missing.add(requiredPlugin.simpleName ?: requiredPlugin.jvmName)
        }
        if (missing.isNotEmpty()) {
            error("Plugin(s) ${missing.joinToString(separator = ", ")} required but not passed to Kweb constructor")
        }
    }

    fun execute(js: String) {
        kweb.execute(sessionId, js)
    }

    fun executeWithCallback(js: String, callbackId: Int, callback: (Any) -> Unit) {
        kweb.executeWithCallback(sessionId, js, callbackId, callback)
    }

    fun removeCallback(callbackId: Int) {
        kweb.removeCallback(sessionId, callbackId)
    }

    fun evaluate(js: String): CompletableFuture<Any> {
        val cf = CompletableFuture<Any>()
        evaluateWithCallback(js) { response ->
            cf.complete(response)
            false
        }
        return cf
    }

    fun evaluateWithCallback(js: String, rh: (Any) -> Boolean) {
        kweb.evaluate(sessionId, js) { rh.invoke(it) }
    }

    fun send(instruction: Instruction) = send(listOf(instruction))

    fun send(instructions: List<Instruction>) {
        kweb.send(sessionId, instructions)
    }

    val doc = Document(this)

    /**
     * The URL of the page, relative to the origin - so for the page `http://foo/bar?baz#1`, the value would be
     * `/bar?baz#1`.
     *
     * When this KVar is modified the browser will automatically update the URL in the browser along with any DOM
     * elements based on this [url] (this will be handled automatically by [kweb.routing.route]).
     */
    val url: KVar<String>
            by lazy {
                val originRelativeURL = URL.parse(httpRequestInfo.requestedUrl).pathQueryFragment
                val url = KVar(originRelativeURL)

                url.addListener { _, newState ->
                    pushState(newState)
                }

                url
            }

    private fun pushState(url: String) {
        if (!url.startsWith('/')) {
            logger.warn("pushState should only be called with origin-relative URLs (ie. they should start with a /)")
        }
        execute("""
        history.pushState({}, "", "$url");
        """.trimIndent())
    }

    fun <T : Any> url(mapper: (String) -> T) = url.map(mapper)

    fun <T : Any> url(func: ReversibleFunction<String, T>) = url.map(func)

}
