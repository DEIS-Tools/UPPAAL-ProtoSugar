package uppaal_pojo

import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

@Root(name = "nta")
class Nta : UppaalPojo {
    @field:Element(name = "declaration")
    lateinit var declaration: Declaration

    @field:ElementList(name = "template", required = false, inline = true)
    lateinit var templates: List<Template>

    @field:Element(name = "system")
    lateinit var system: System

    @field:ElementList(name = "queries")
    lateinit var queries: List<Query>
}