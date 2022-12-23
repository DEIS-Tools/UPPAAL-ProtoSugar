package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root

@Root(name = "branchpoint")
class Branchpoint : UppaalPojo {
    @field:Attribute(name = "id")
    lateinit var id: String

    @field:Attribute(name = "x")
    var x: Int = 0

    @field:Attribute(name = "y")
    var y: Int = 0
}