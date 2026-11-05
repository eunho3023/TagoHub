// 대중교통 탭 안의 수단 화면들을 연결 (more.js 의 modeScreens)
import { modeScreens } from "./more.js";
import { subwaySearch } from "./subway.js";
import { trainScreen, coachScreen } from "./trips.js";
import { airScreen } from "./air.js";

modeScreens.subway = (root, chipsHtml) => subwaySearch(root, chipsHtml);
modeScreens.train = (root, chipsHtml) => trainScreen(root, chipsHtml);
modeScreens.coach = (root, chipsHtml) => coachScreen(root, chipsHtml);
modeScreens.air = (root, chipsHtml) => airScreen(root, chipsHtml);
import { roadScreen } from "./road.js";
modeScreens.road = (root, chipsHtml, ctx) => roadScreen(root, chipsHtml, ctx);
