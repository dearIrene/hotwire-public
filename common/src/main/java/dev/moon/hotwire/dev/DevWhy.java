package dev.moon.hotwire.dev;

public record DevWhy(String type, String method, int line, double timeA, double timeB, double madeA, double madeB) {
    public String findName() {
        return this.type.substring(this.type.lastIndexOf('.') + 1) + "." + this.method;
    }
}
