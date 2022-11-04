package uppaal_pojo

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "label")
class Label() : UppaalPojo {
    @field:Attribute(name = "kind")
    lateinit var kind: String

    @field:Attribute(name = "x")
    var x: Int = 0

    @field:Attribute(name = "y")
    var y: Int = 0

    @field:Text
    lateinit var content: String

    constructor(initKind: String, initX: Int, initY: Int) : this() {
        kind = initKind
        x = initX
        y = initY
        content = ""
    }
}