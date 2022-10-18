package uppaal_pojo

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root

@Root(name = "target")
class Target {
    @field:Attribute(name = "ref")
    lateinit var ref: String
}