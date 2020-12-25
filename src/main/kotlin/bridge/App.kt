package bridge

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties

@SpringBootApplication
@EnableConfigurationProperties
class App

fun main(args: Array<String>) {
    val application = SpringApplication(App::class.java)
    application.run(*args)
}