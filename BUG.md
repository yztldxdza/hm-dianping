#### BUG1

出现位置29集

场景：使用验证码登录后跳转到index页面，再次点击“我的”仍要再次登录

​			此时IDEA报错：UserHolder.saveUser((UserDTO)user);这句中的user为空

​			位置：utils包中LoginInterceptor类29行

解决方法：

​		后端：

​			1.将UserHolder.saveUser((UserDTO)user);  改为UserHolder.saveUser(user);

​			2.将Object user =session.getAttribute("user");改为User user = (User) session.getAttribute("user");

​			3.将UserHolder中的UserDTO全部改成User

​		前端：

​			1.修改nginx下的html文件夹中login.html login()方法的跳转页面到/info.html  (87行左右)

​			2.在info.html中queryUser()方法的then()方法末尾加上location.href = 'info.html'  (163行左右)