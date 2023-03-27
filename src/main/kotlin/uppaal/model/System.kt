package uppaal.model

import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "system")
class System : TextUppaalPojo {
    @field:Text
    override var content: String = ""

    override var parent: UppaalPojo? = null
}