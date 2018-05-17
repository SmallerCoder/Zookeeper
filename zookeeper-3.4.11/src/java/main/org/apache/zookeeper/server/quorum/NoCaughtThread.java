package org.apache.zookeeper.server.quorum;

import java.lang.Thread.UncaughtExceptionHandler;

public class NoCaughtThread {

	public static void main(String[] args) {
		// 设置未捕获异常处理器，这里是默认的，当然你可以自己新建一个类，然后实现UncaughtExceptionHandler接口即可
		Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {

			@Override
			public void uncaughtException(Thread t, Throwable e) {
				System.err.println("程序抛出了一个异常，异常类型为 ： " + e);
			}
		});
		Thread thread = new Thread(new Task());
		thread.start();
	}
}

class Task implements Runnable {

	@Override
	public void run() {
		throw new NullPointerException();
	}

}
