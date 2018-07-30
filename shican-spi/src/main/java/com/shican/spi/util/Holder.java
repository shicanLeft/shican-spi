package com.shican.spi.util;

public class Holder<T> {
	
	private volatile T t;

	public T getT() {
		return t;
	}

	public void setT(T t) {
		this.t = t;
	}

}
