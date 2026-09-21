package paceline.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class AssetsController {
    @GetMapping("/", "/library", "/ride")
    fun appShell(): String = "forward:/index.html"
}
