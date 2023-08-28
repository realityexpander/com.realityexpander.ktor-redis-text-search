package com.realityexpander

import com.realityexpander.plugins.*
import com.redis.lettucemod.RedisModulesClient
import com.redis.lettucemod.api.StatefulRedisModulesConnection
import com.redis.lettucemod.api.sync.RedisModulesCommands
import com.redis.lettucemod.search.CreateOptions
import com.redis.lettucemod.search.Field
import com.redis.lettucemod.search.SearchOptions
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.coroutines
import io.lettuce.core.dynamic.Commands
import io.lettuce.core.dynamic.RedisCommandFactory
import io.lettuce.core.dynamic.annotation.Command
import io.lettuce.core.dynamic.annotation.CommandNaming
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

val jsonConfig = Json {
    prettyPrint = true
    isLenient = true
}

val ktorLogger: ch.qos.logback.classic.Logger =
    LoggerFactory.getLogger("KTOR-WEB-APP") as ch.qos.logback.classic.Logger

// Define the Redis Search Commands
// Yes, its odd that we have to define the commands this way, but it's how it works.
interface RedisSearchCommands : Commands {
    @Command("FT.CREATE")
    @CommandNaming(strategy = CommandNaming.Strategy.DOT)
    fun ftCreate(index: String, vararg args: String): String

    @Command("FT.CONFIG SET")
    @CommandNaming(strategy = CommandNaming.Strategy.DOT)
    fun ftConfigSet(key: String, value: String): String
}

@OptIn(ExperimentalLettuceCoroutinesApi::class) // for coroutines()
fun Application.module() {
    configureSerialization()
    configureRouting()

    val redisClient: RedisModulesClient = RedisModulesClient.create("redis://localhost:6379")
    val redisConnection: StatefulRedisModulesConnection<String, String> = redisClient.connect()

    val redisSyncCommand: RedisModulesCommands<String, String> = redisConnection.sync()
    val redisCoroutineCommand = redisConnection.coroutines()
    val redisReactiveCommand = redisConnection.reactive()

    // setup the search commands not included in libraries
    val redisSearchCommands = RedisCommandFactory(redisConnection).getCommands(RedisSearchCommands::class.java)

    // allow one character strings for FT.SEARCH
    redisSearchCommands.ftConfigSet("MINPREFIX", "1")

    // Build the JSON Text search indexes
    try {
        // check if index exists
        val result = redisSyncCommand.ftInfo("users_index")
    } catch (e: Exception) {
        // setup json text search index
        val result = redisSyncCommand.ftCreate(
            "users_index",
            CreateOptions.builder<String, String>()
                .prefix("user:")
                .on(CreateOptions.DataType.JSON)
                .build(),
            Field.tag("$.id")  // note: TAGs do not separate words/special characters
                .`as`("id")
                .build(),
            Field.tag("$.email")
                .`as`("email")
                .build(),
            Field.text("$.name")
                .`as`("name")
                .sortable()
                .withSuffixTrie()  // for improved search (go -> going, goes, gone)
                .build()
        )

        if (result != "OK") {
            ktorLogger.error("Error creating index: $result")
        }
    }

    // Run some basic tests
    val resultRedisAdd1 = redisSyncCommand.jsonSet(
        "user:1",
        "$", // path
        """
        {
            "id": "00000000-0000-0000-0000-000000000001",
            "email": "chris@alpha.com",
            "name": "Chris"
        }
    """.trimIndent()
    )
    println("resultRedisAdd1: $resultRedisAdd1")

    val resultRedisAdd2 = redisSyncCommand.jsonSet(
        "user:2",
        "$",
        """
        {
            "id": "00000000-0000-0000-0000-000000000002",
            "email": "billy@beta.com",
            "name": "Billy"
        }
    """.trimIndent()
    )
    println("resultRedisAdd2: $resultRedisAdd2")

    val escapedSearchId = "0000-000000000001".escapeRedisSearchSpecialCharacters()
    val resultIdSearch = redisSyncCommand.ftSearch(
        "users_index",
        "@id:{*$escapedSearchId*}" // search for '0000-000000000001' in id
    )
    println("resultIdSearch: $resultIdSearch")

    val resultTagSearch = redisSyncCommand.ftSearch(
        "users_index",
        "@email:{*ch*}" // search for 'ch' in email, note use curly-braces for TAG type
    )
    println("resultTagSearch: $resultTagSearch")

    val resultTextSearch = redisSyncCommand.ftSearch(
        "users_index",
        "@name:*bi*" // search for 'bi' in name, note NO curly-braces for TEXT type
    )
    println("resultTextSearch: $resultTextSearch")

    @Serializable
    data class UserSearchResult(
        val id: String,
        val email: String,
        val name: String,
    )

    val resultArray = resultTagSearch.map { resultMap ->
        val resultValue = resultMap.get("$") as String
        jsonConfig.decodeFromString<UserSearchResult>(resultValue)
    }
    println("resultArray: $resultArray")

    // Setup the Ktor application web server to respond to http requests for Redis content
    routing {
        route("/redis") {
            get("/keys") {
                val keys = redisCoroutineCommand.keys("*")
                val output: ArrayList<String> = arrayListOf()
                keys.collect { key ->
                    output += key
                }
                call.respondJson(mapOf("keys" to output.toString()))
            }

            get("/jsonGet") {
                val key = call.request.queryParameters["key"]
                key ?: run {
                    call.respondJson(mapOf("error" to "Missing key"), HttpStatusCode.BadRequest)
                    return@get
                }
                val paths = call.request.queryParameters["paths"] ?: "$"

                val value = redisReactiveCommand.jsonGet(key, paths) ?: run {
                    call.respondJson(mapOf("error" to "Key not found"), HttpStatusCode.NotFound)
                    return@get
                }
                call.respondJson(mapOf("key" to key, "value" to (value.block()?.toString() ?: "null")))
            }

            get("/jsonSet") {
                val key = call.request.queryParameters["key"] ?: run {
                    call.respondJson(mapOf("error" to "Missing key"), HttpStatusCode.BadRequest)
                    return@get
                }
                val paths = call.request.queryParameters["paths"] ?: "$"
                val value = call.request.queryParameters["value"] ?: run {
                    call.respondJson(mapOf("error" to "Missing value"), HttpStatusCode.BadRequest)
                    return@get
                }

                val result = redisReactiveCommand.jsonSet(key, paths, value) ?: run {
                    call.respondJson(mapOf("error" to "Failed to set key"), HttpStatusCode.InternalServerError)
                    return@get
                }
                call.respondJson(mapOf("success" to Json.encodeToString(result.block())))
            }

            get("/jsonFind") {
                val index = call.request.queryParameters["index"] ?: run {
                    call.respondJson(mapOf("error" to "Missing index"), HttpStatusCode.BadRequest)
                    return@get
                }
                val query = call.request.queryParameters["query"] ?: run {
                    call.respondJson(mapOf("error" to "Missing query"), HttpStatusCode.BadRequest)
                    return@get
                }

                val result =
                    redisSyncCommand.ftSearch(
                        index,
                        query,
                        SearchOptions.builder<String, String>()
                            .limit(0, 100)
                            .withSortKeys(true)
                            .build()
                    ) ?: run {
                        call.respondJson(mapOf("error" to "Failed to find key"), HttpStatusCode.InternalServerError)
                        return@get
                    }
                val searchResults: List<Map<String, String>> =
                    result.map { document ->
                        document.keys.map { key ->
                            key to document.get(key).toString()
                        }.toMap()
                    }
                call.respondJson(mapOf("success" to Json.encodeToString(searchResults)))
            }
        }
    }
}

fun String.escapeRedisSearchSpecialCharacters(): String {
    val escapeChars =
        """
                ,.<>{}[]"':;!@#$%^&*()-+=~"
                """.trimIndent()
    var result = this

    escapeChars.forEach {
        result = result.replace(it.toString(), "\\$it")
    }

    return result
}

suspend fun ApplicationCall.respondJson(
    map: Map<String, String> = mapOf(),
    status: HttpStatusCode = HttpStatusCode.OK
) {
    respondText(jsonConfig.encodeToString(map), ContentType.Application.Json, status)
}


