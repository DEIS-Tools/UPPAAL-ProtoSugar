package uppaal.model

import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "system")
class System : UppaalPojo {
    @field:Text
    lateinit var content: String
}