package lv.ray.springb.controller;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;


@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DemoController
{

	@GetMapping("/hello")
	public Map<String, String> sayHello(@RequestParam(defaultValue = "World") final String name)
	{
		final Map<String, String> response = new HashMap<>();
		response.put("message", "Hello, " + name + "!");
		response.put("timestamp", String.valueOf(System.currentTimeMillis()));

		return response;
	}
}
