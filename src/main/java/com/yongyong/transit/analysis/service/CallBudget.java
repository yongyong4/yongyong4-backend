package com.yongyong.transit.analysis.service;

/**
 * 요청 1번 안에서 GBIS를 몇 번까지 부를지 세는 카운터 (캐시 사용은 세지 않음).
 * 요청마다 새로 만든다. 재평가처럼 GBIS를 부르면 안 되는 경우는 none()을 쓴다.
 */
public class CallBudget {

    private final int limit;
    private int used;
    private boolean limitReached;

    private CallBudget(int limit) {
        this.limit = limit;
    }

    public static CallBudget of(int limit) {
        return new CallBudget(limit);
    }

    public static CallBudget none() {
        return new CallBudget(0);
    }

    public static CallBudget unlimited() {
        return new CallBudget(Integer.MAX_VALUE);
    }

    public synchronized boolean hasRemaining() {
        if (used < limit) {
            return true;
        }

        limitReached = true;

        return false;
    }

    public synchronized void use() {
        used++;
    }

    public synchronized int used() {
        return used;
    }

    // 한도 때문에 GBIS를 못 부른 적이 있으면 true (dataDelayed 판단용).
    // none()은 캐시에 없는 조회가 한 번이라도 있으면 true가 된다.
    public synchronized boolean limitReached() {
        return limitReached;
    }
}
