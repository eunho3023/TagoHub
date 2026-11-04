
# 카카오맵 SDK v2 (공식 문서의 프로가드 설정)
-keep class com.kakao.vectormap.** { *; }
-keep interface com.kakao.vectormap.**

# 오류 보고에서 줄 번호를 읽을 수 있게 (난독화 매핑은 app/build/outputs/mapping/release/mapping.txt, 배포 때마다 보관)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp 가 선택적으로 쓰는 보안 라이브러리 (없어도 됨)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
