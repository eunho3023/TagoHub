/// <reference types="node" />
// node --test 로 돌림 (Node 22.6 이상은 TypeScript 를 그대로 읽음)
import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  distanceM, distanceText, etaText, friendlyError, lineColor, lineLabel, normalizeBase, remaining, routeColor, subwayEta, subwayOrder,
} from './format.ts';

test('버스 도착 시간 글', () => {
  assert.equal(etaText(null), '정보없음');
  assert.equal(etaText(20), '곧 도착');
  assert.equal(etaText(45), '45초');
  assert.equal(etaText(185), '3분 05초');
});

test('받은 뒤 흐른 시간만큼 줄어듦', () => {
  assert.equal(remaining(100, 1_000, 11_000), 90);
  assert.equal(remaining(5, 0, 60_000), 0);
  assert.equal(remaining(null, 0, 0), null);
});

test('지하철 도착 글과 순서', () => {
  assert.equal(subwayEta(40, null), '곧 도착');
  assert.equal(subwayEta(130, null), '3분');
  assert.equal(subwayEta(0, '[3]번째 전역 (신촌)'), '3정거장 전');
  assert.ok(subwayOrder(0, '당역 도착') < subwayOrder(120, null));
  assert.ok(subwayOrder(120, null) < subwayOrder(0, '[5]번째 전역'));
});

test('노선 색과 짧은 이름', () => {
  assert.equal(routeColor('간선버스'), '#3D5BAB');
  assert.equal(routeColor(undefined), '#607080');
  assert.equal(lineColor('2호선'), '#00A84D');
  assert.equal(lineColor('수도권 2호'), '#00A84D');
  assert.equal(lineColor('부산김해경전철'), '#4F5B6B');
  assert.equal(lineLabel('2호선'), '2');
  assert.equal(lineLabel('공항철도'), '공항');
});

test('거리', () => {
  const d = distanceM(37.5665, 126.978, 37.5651, 126.9895);
  assert.ok(d > 950 && d < 1100, String(d));
  assert.equal(distanceText(950), '950m');
  assert.equal(distanceText(1234), '1.2km');
});

test('서버 주소 정리', () => {
  assert.equal(normalizeBase(' 192.168.0.5:8000/ '), 'http://192.168.0.5:8000');
  assert.equal(normalizeBase('https://tagohub.run.app'), 'https://tagohub.run.app');
  assert.equal(normalizeBase('   '), '');
});

test('공공 API 오류를 읽기 쉽게', () => {
  assert.match(friendlyError('TAGO 오류 403: {"errMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}'), /등록/);
  assert.equal(friendlyError('서버에 연결하지 못했어요.'), '서버에 연결하지 못했어요.');
  assert.ok(friendlyError('x'.repeat(500)).length <= 201);
});
