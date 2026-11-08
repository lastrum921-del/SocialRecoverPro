// Sustituto mínimo de org.junit.Test para ejecutar las pruebas SIN Gradle ni Maven
// (útil en entornos sin acceso a repositorios). En Android Studio se usa JUnit 4 real.
package org.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
