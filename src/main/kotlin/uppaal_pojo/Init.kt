package uppaal_pojo

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root

@Root(name = "init")
class Init {
    @field:Attribute(name = "ref")
    lateinit var ref: String
}