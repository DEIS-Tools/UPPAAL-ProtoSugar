package uppaal.model

import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

@Root(name = "nta", strict = false)
class Nta : UppaalPojo {
    @field:Element(name = "declaration")
    lateinit var declaration: Declaration

    @field:ElementList(name = "template", required = false, inline = true)
    var templates: List<Template> = ArrayList()

    @field:Element(name = "system")
    lateinit var system: System
}