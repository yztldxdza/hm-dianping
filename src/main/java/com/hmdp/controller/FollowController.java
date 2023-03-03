package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  好友关注
 * </p>
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    //判断当前用户是否关注了该博主
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId){
        return followService.isFollow(followUserId);
    }

    //实现取关/关注
    @PutMapping("/{id}/{isFollow}")
    public Result follw(@PathVariable("id") Long followUserId,@PathVariable("isFollow") Boolean isFellow){
        return followService.follow(followUserId,isFellow);
    }
}
