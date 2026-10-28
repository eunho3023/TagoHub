package com.eunho.tagohub

import com.eunho.tagohub.data.NavText
import org.junit.Assert.assertEquals
import org.junit.Test

class NavWordingTest {
    @Test
    fun nearlyOneKilometerIsReadAsKilometer() {
        assertEquals("1킬로미터", NavText.distance(980.0))
        assertEquals("1킬로미터", NavText.distance(1000.0))
        assertEquals("950미터", NavText.distance(950.0))
    }

    @Test
    fun withinAHundredFiftyMetersItSaysSoonInsteadOfADistance() {
        assertEquals("잠시 후", NavText.ahead(150.0))
        assertEquals("잠시 후", NavText.ahead(40.0))
        assertEquals("200미터 앞", NavText.ahead(200.0))
        assertEquals("1킬로미터 앞", NavText.ahead(1000.0))
    }
}
