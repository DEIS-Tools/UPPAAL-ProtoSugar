package uppaal_pojo

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root

@Root(name = "nail")
class Nail : UppaalPojo {
    @field:Attribute(name = "x")
    var x: Int = 0

    @field:Attribute(name = "y")
    var y: Int = 0
}