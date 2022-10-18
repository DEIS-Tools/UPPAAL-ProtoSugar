package uppaal_pojo

import org.simpleframework.xml.*
import java.util.LinkedList

@Root(name = "template")
class Template {
    @field:Element(name = "name", required = false)
    var name: String? = null

    @field:Element(name = "parameter", required = false)
    var parameter: String? = null

    @field:Element(name = "declaration", required = false)
    var declaration: String? = null

    @field:ElementList(name = "location", required = false, inline = true)
    var locations: List<Location>? = null

    @field:ElementList(name = "branchpoint", required = false, inline = true)
    var branchpoint: List<Branchpoint>? = null

    @field:Element(name = "init", required = false)
    var init: Init? = null

    @field:ElementList(name = "transition", required = false, inline = true)
    var transitions: LinkedList<Transition>? = null
}