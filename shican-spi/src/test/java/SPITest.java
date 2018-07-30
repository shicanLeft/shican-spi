import org.junit.Test;

import com.shican.spi.car.Car;
import com.shican.spi.loader.ShiCanExtensionLoader;

public class SPITest {

    @Test
    public void test() throws Exception {
        Car bigCar = ShiCanExtensionLoader.
                getExtensionLoader(Car.class).
                getExtension("bigCar");
        bigCar.drive();
        
        
        Car SmallCar = ShiCanExtensionLoader.
                getExtensionLoader(Car.class).
                getDefaultExtension();
        SmallCar.drive();
    }
}
