## Ktor Redis Text Search

Shows how to use Redis with Ktor and Kotlin to perform Json full text searches

https://stackoverflow.com/questions/76973632/how-to-use-redis-with-ktor-to-do-text-searches-with-json-data-types/76973857#76973857


### Start the Ktor server, and you can use any browser (or Postman) to do the following commands:

Start the Ktor server:

`./gradlew run`

In Browser:

- To get json object in redis database
  - http://localhost:8081/redis/jsonGet?key={keyId}&paths={paths}
  - Example:
  - http://localhost:8081/redis/jsonGet?key=user:1&paths=$
  - http://localhost:8081/redis/jsonGet?key=user:1&paths=.name

- To set json object in redis database
  - http://localhost:8081/redis/jsonSet?key={keyId}&paths={paths}&value={value}
  - Example
  - http://localhost:8081/redis/jsonSet?key=user:1&paths=$.name&value=Jimmy

- To query json object fields
  - http://localhost:8081/redis/jsonFind?index={index}&query=@{field}:{searchQuery}
  - Example
  - http://localhost:8081/redis/jsonFind?index=users_index&query=%27@name:bil*%27

- To Dump all keys in redis database
  - http://localhost:8081/redis/keys

    