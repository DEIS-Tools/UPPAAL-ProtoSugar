package uppaal.model

import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

@Root(name = "nta", strict = false)
class Nta : UppaalPojo {
    @field:Element(name = "declaration")
    lateinit var declaration: Declaration

    @field:ElementList(name = "template", required = false, inline = true)
    var templates: MutableList<Template> = ArrayList()

    @field:Element(name = "system")
    lateinit var system: System


    lateinit var schemaInfo: String
    override var parent: UppaalPojo? = null
}