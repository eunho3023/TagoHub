import unittest

from fetch_city_times import clock_list


class ClockListTest(unittest.TestCase):
    def test_clock_list(self):
        self.assertEqual(clock_list([" 06:00", "0615", "25:99", "06:00"]), [360, 375])


if __name__ == "__main__":
    unittest.main()
