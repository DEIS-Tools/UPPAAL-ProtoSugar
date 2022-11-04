package uppaal_pojo

import org.simpleframework.xml.*

@Root(name = "template")
class Template : UppaalPojo {
    @field:Element(name = "name", required = false)
    var name: String? = null

    @field:Element(name = "parameter", required = false)
    var parameter: Parameter? = null

    @field:Element(name = "declaration", required = false)
    var declaration: Declaration? = null

    @field:ElementList(name = "location", required = false, inline = true)
    var locations: List<Location>? = null

    @field:ElementList(name = "branchpoint", required = false, inline = true)
    var branchpoint: List<Branchpoint>? = null

    @field:Element(name = "init", required = false)
    var init: Init? = null

    @field:ElementList(name = "transition", required = false, inline = true)
    var transitions: List<Transition>? = null
}