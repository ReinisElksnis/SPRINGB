package lv.ray.springb.controller;

import lv.ray.springb.constants.ApiConstants;
import lv.ray.springb.constants.ApiConstants.Defaults;
import lv.ray.springb.constants.ApiConstants.Endpoints;
import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.constants.ApiConstants.ResponseKeys;
import lv.ray.springb.constants.ApiConstants.SubPaths;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;


@RestController
@RequestMapping(Endpoints.API)
@CrossOrigin(origins = ApiConstants.ALL_ORIGINS)
public class DemoController
{

	@GetMapping(SubPaths.HELLO)
	public Map<String, String> sayHello(@RequestParam(defaultValue = Defaults.GREETING_NAME) final String name)
	{
		final Map<String, String> response = new HashMap<>();
		response.put(ResponseKeys.MESSAGE, String.format(Messages.GREETING, name));
		response.put(ResponseKeys.TIMESTAMP, String.valueOf(System.currentTimeMillis()));

		return response;
	}
}
