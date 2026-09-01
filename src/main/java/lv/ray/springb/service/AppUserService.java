package lv.ray.springb.service;

import lv.ray.springb.dto.RegistrationRequest;
import lv.ray.springb.entity.AppUser;

import java.util.Optional;


public interface AppUserService
{
	AppUser register(RegistrationRequest request);

	Optional<AppUser> getByUsername(String username);
}
