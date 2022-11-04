package uppaal_pojo

import org.simpleframework.xml.Element
import org.simpleframework.xml.Root

@Root(name = "query")
class Query : UppaalPojo {
    @field:Element(name = "formula", required = false)
    lateinit var formula: String

    @field:Element(name = "comment", required = false)
    lateinit var comment: String

    @field:Element(name = "result", required = false)
    lateinit var result: String
}