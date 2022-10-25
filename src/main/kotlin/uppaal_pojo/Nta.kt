package uppaal_pojo

import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

@Root(name = "nta")
class Nta {
    @field:Element(name = "declaration")
    lateinit var declarations: String

    @field:ElementList(name = "template", required = false, inline = true)
    lateinit var templates: List<Template>

    @field:Element(name = "system")
    lateinit var system: String

    @field:ElementList(name = "queries")
    lateinit var queries: List<Query>
}