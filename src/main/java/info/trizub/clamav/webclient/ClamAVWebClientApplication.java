/**
 * Class Name: ClamAVWebClientApplication
 * Description: This is the main class to run Spring MVC application.
 * 
 * Author: Ruslan Huzii
 * Email:  ruslan@trizub.info
 * Date: 2024-11-04
 * 
 * License: GNU Lesser General Public License v2.1
 */
package info.trizub.clamav.webclient;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ClamAVWebClientApplication {

	public static void main(String[] args) throws Exception {
		info.trizub.clamav.webclient.configuration.ConfigurationBootstrap.run(ClamAVWebClientApplication.class,args);
	}

}
