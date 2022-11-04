package uppaal_pojo

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root

@Root(name = "result")
class Result : UppaalPojo {
    @field:Attribute(name = "outcome")
    lateinit var outcome: String

    @field:Attribute(name = "type")
    lateinit var type: String

    @field:Attribute(name = "timestamp")
    lateinit var timestamp: String
}