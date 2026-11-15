package app.rilan.daily.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherPlaceTest {

    private fun weather(city: String, place: String) = WeatherData(
        city = city,
        place = place,
        updatedAt = 0L,
        currentTemp = 0.0,
        feelsLike = 0.0,
        humidity = 0,
        windSpeed = 0.0,
        windDirection = 0,
        weatherCode = 0,
        isDay = true,
        high = 0.0,
        low = 0.0,
    )

    @Test
    fun localityLabelKeepsDistrictAndStreet() {
        val data = weather("杭州", "杭州 · 西湖区 · 北山街道")
        assertEquals("杭州 · 西湖区 · 北山街道", data.locationLabel)
        assertEquals("西湖区 · 北山街道", data.localityLabel)
    }

    @Test
    fun oldCacheWithoutPlaceFallsBackToCity() {
        val data = weather("北京", "")
        assertEquals("北京", data.locationLabel)
        assertEquals("", data.localityLabel)
    }
}
