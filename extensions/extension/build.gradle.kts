extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.yydarlinker.extension"
}


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
android { testOptions { unitTests.isReturnDefaultValues = true } }

dependencies { testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0") }
