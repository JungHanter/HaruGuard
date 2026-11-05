package com.hanter.haruguard.vpn

/**
 * 2026-11-05 확인. 서비스별 공개 목록을 참고해 공유 상위 도메인은 제외한다.
 * https://github.com/nextdns/services (MIT 고지를 assets/licenses에 포함)
 * 패키지 출처: https://play.google.com/store/apps/details?id=<각 FilterService.packageName>
 */
internal object FilterCatalog {
    val domains: Map<FilterService, Set<String>> = mapOf(
        FilterService.YOUTUBE to setOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com",
            "googlevideo.com", "ytimg.com", "youtubei.googleapis.com",
            "youtube.googleapis.com", "youtubeembeddedplayer.googleapis.com",
        ),
        FilterService.TIKTOK to setOf(
            "muscdn.com", "muscdn.com.akamaized.net", "musical.ly",
            "p16-tiktokcdn-com.akamaized.net", "tiktok.bytedance.map.fastly.net", "tiktok.mncdn.com",
            "tiktok.com", "tiktok.com.bytedance.akadns.net", "tiktok.com.edgesuite.net",
            "tiktok.com.ttdns2.com", "tiktok.net", "tiktok-eu.net",
            "tiktok-row.net", "tiktok-us.net", "tiktokcdn.com",
            "tiktokcdn.com.abc-jns.swiftserve.com", "tiktokcdn.com.atomile.com", "tiktokcdn.com.c.bytetcdn.com",
            "tiktokcdn.com.c.bytefcdn-ttpeu.com", "tiktokcdn.com.c.worldfcdn.com", "tiktokcdn.com.jns3.swiftserve.com",
            "tiktokcdn.com.akamaized.net", "tiktokcdn.com.bytegeo.akadns.net", "tiktokcdn.com.c.bytefcdn-oversea.com",
            "tiktokcdn.com.cdn20.com", "tiktokcdn.com.ecdnx.com", "tiktokcdn.com.edgesuite.net",
            "tiktokcdn.com.qlivecdn.com", "tiktokcdn.com.queniuaa.com", "tiktokcdn.com.rocket-cdn.com",
            "tiktokcdn.com.tlivepush.com", "tiktokcdn.com.ttdns2.com", "tiktokcdn.com.wsdvs.com",
            "tiktokcdn.liveplay.myqcloud.com", "tiktokcdn-eu.com", "tiktokcdn-eu.com.c.bytefcdn-ttpeu.com",
            "tiktokcdn-eu.com.ttdns3.com", "tiktokcdn-eu.net", "tiktokcdn-in.com",
            "tiktokcdn-us.com", "tiktokcdn-us.com.atomile.com", "tiktokcdn-us.com.c.worldfcdn2.com",
            "tiktokcdn-us.com.edgesuite.net", "tiktokd.org", "tiktokeu-cdn.com",
            "tiktokeu-cdn.com.c.bytefcdn-ttpeu.com", "tiktokglobalshopv.com", "tiktoklb.eu",
            "tiktokrow-cdn.com", "tiktokrow-cdn.com.c.bytefcdn-oversea.com", "tiktokrow-cdn.com.qlivecdn.com",
            "tiktokrow-cdn.com.rocket-cdn.com", "tiktokrow-cdn.com.wsdvs.com", "tiktokshop.com",
            "tiktokstaticb.com", "tiktokv.com", "tiktokv.com.bytewlb.akadns.net",
            "tiktokv.com.c.worldfcdn2.com", "tiktokv.com.edgekey.net", "tiktokv.com.edgesuite.net",
            "tiktokv.com.ttdns2.com", "tiktokv.eu", "tiktokv.eu.edgesuite.net",
            "tiktokv.eu.ttdns3.com", "tiktokv.us", "tiktokw.eu",
            "tiktokw.us", "ttlivecdn.com", "tiktokcdn-eu.com.edgesuite.net",
            "tiktokcdn-eu.com.akamaized.net", "tiktokv.us.edgesuite.net", "tiktokv.com.akamaized.net",
            "tiktokw.us.edgesuite.net", "tiktokv.com.c.bytefcdn-oversea.com", "tiktok.in",
            "tiktokcdn-us.com.akamaized.net", "tiktokstaticb.com.edgesuite.net", "tiktok.com.bytewlb.akadns.net",
            "tiktokv.com.cdn.cloudflare.net",
        ),
        FilterService.INSTAGRAM to setOf(
            "cdninstagram.com", "instagram.com",
        ),
        FilterService.FACEBOOK to setOf(
            "facebook.com", "fb.com", "fb.me",
        ),
        FilterService.X to setOf(
            "ads-twitter.com", "t.co", "twimg.com",
            "twitter.com", "twttr.com", "twtrdns.net",
            "x.com",
        ),
        FilterService.VIMEO to setOf(
            "vimeo-video.map.fastly.net", "vimeo.com", "vimeo.map.fastly.net",
            "vimeocdn.com",
        ),
    )

    // 공개 후보 중 채팅/이미지/쇼핑 공유 호스트는 제외했다. 기기에서 차단 효과는 미검증이다.
    // https://github.com/SecuredEevee/kakao_adblock
    // https://netxhack.com/hack/block-kakaotalk-shorts/
    val kakaoDefaults = setOf("play.kakao.com", "shortformstats.kakao.com")

    // 네이버 공식 숏폼 뷰어가 참조하는 전용 호스트 후보. 공유 apis.naver.com은 제외한다.
    // https://m.naver.com/shorts/ (clipViewerHost 및 공개 스크립트, 2026-11-05)
    val naverDefaults = setOf("clip.naver.com", "clip-viewer.naver.com", "creatorhub-api.naver.com")
}
