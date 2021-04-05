package kweb.html

import kweb.Element
import kweb.WebBrowser

class HeadElement(webBrowser: WebBrowser, id: String) : Element(webBrowser, null, "head", id)
open class TitleElement(parent: Element) : Element(parent)
