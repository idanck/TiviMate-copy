package com.idanck.shidur.auto

import com.idanck.shidur.data.radio.RadioRegion
import com.idanck.shidur.data.radio.RadioShelf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The car's folders on the radio menu's shelves (ADR-064): which folder a shelf goes to, the
 * order a folder lists its stations in, and which folder a tapped station's queue walks.
 */
class CarFoldersTest {

    private val south = RadioShelf.Regional(RadioRegion.SOUTH)
    private val north = RadioShelf.Regional(RadioRegion.NORTH)
    private val elsewhere = RadioShelf.Regional(RadioRegion.UNKNOWN)
    private val mizrahi = RadioShelf.Genre("מזרחי")
    private val greek = RadioShelf.Genre("יווני")

    // In provider order, as the database hands them over.
    private val stations = listOf(
        "kan" to listOf<RadioShelf>(RadioShelf.National),
        "darom" to listOf<RadioShelf>(south),
        "shfela" to listOf<RadioShelf>(elsewhere),
        "katzev" to listOf(RadioShelf.National, mizrahi),
        "haifa" to listOf<RadioShelf>(north),
        "kahol" to listOf(RadioShelf.National, greek),
        "galgalatz" to listOf<RadioShelf>(RadioShelf.National),
    )

    @Test
    fun `national keeps the provider's order`() {
        assertEquals(
            listOf("kan", "katzev", "kahol", "galgalatz"),
            stations.inCarFolder(CarFolder.NATIONAL).map { it.first },
        )
    }

    @Test
    fun `the regions folder runs north to south, then other regions`() {
        val regions = stations.inCarFolder(CarFolder.REGIONS)
        assertEquals(listOf("haifa", "darom", "shfela"), regions.map { it.first })
        assertEquals(listOf<RadioShelf>(north, south, elsewhere), regions.map { it.second })
    }

    @Test
    fun `the genres folder is alphabetical by genre`() {
        assertEquals(listOf("kahol", "katzev"), stations.inCarFolder(CarFolder.GENRES).map { it.first })
    }

    @Test
    fun `a station's queue is its place's folder, never its genre's`() {
        assertEquals(CarFolder.NATIONAL, placeFolder(listOf(RadioShelf.National, mizrahi)))
        assertEquals(CarFolder.REGIONS, placeFolder(listOf(north)))
        assertEquals(CarFolder.NATIONAL, placeFolder(listOf(mizrahi)))
    }
}
