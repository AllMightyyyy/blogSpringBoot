package com.example.blog.controller;

import com.example.blog.model.Post;
import com.example.blog.model.Reaction;
import com.example.blog.model.User;
import com.example.blog.service.PostService;
import com.example.blog.service.ReactionService;
import com.example.blog.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
public class PostController {

    private final PostService postService;
    private final ReactionService reactionService;
    private final UserRepository userRepository;

    @Value("${app.upload.dir}")
    private String uploadDir;

    @Autowired
    public PostController(PostService postService,
                          ReactionService reactionService,
                          UserRepository userRepository) {
        this.postService = postService;
        this.reactionService = reactionService;
        this.userRepository = userRepository;
    }

    @GetMapping("/")
    public String home(Model model) {
        List<Post> posts = postService.findAll();
        model.addAttribute("posts", posts);
        return "home";
    }

    @GetMapping("/posts/create")
    public String showCreatePostForm(Model model) {
        model.addAttribute("post", new Post());
        return "create_post";
    }

    @PostMapping("/posts/create")
    public String createPost(@ModelAttribute Post post,
                             @RequestParam("imageFile") MultipartFile imageFile,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {

        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        post.setAuthor(user);
        post.setCreatedAt(LocalDateTime.now());

        if (!imageFile.isEmpty()) {
            try {
                File uploadFolder = new File(uploadDir);
                if (!uploadFolder.exists()) {
                    uploadFolder.mkdirs();
                }

                String originalFilename = imageFile.getOriginalFilename();
                String extension = "";
                if (originalFilename != null && originalFilename.contains(".")) {
                    extension = originalFilename.substring(originalFilename.lastIndexOf('.'));
                }
                String uniqueFilename = UUID.randomUUID().toString() + extension;

                File destinationFile = Paths.get(uploadDir, uniqueFilename).toFile();
                imageFile.transferTo(destinationFile);

                post.setImagePath("/uploads/" + uniqueFilename);

            } catch (IOException e) {
                e.printStackTrace();
                redirectAttributes.addFlashAttribute("error", "Failed to upload image.");
                return "redirect:/posts/create";
            }
        }

        postService.save(post);
        redirectAttributes.addFlashAttribute("success", "Post created successfully!");
        return "redirect:/";
    }

    @GetMapping("/posts/{id}")
    public String viewPost(@PathVariable Long id, Model model,
                           @RequestParam(value = "error", required = false) String error) {
        Post post = postService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid post Id:" + id));

        long likeCount = reactionService.countByPostAndType(post, "LIKE");
        long dislikeCount = reactionService.countByPostAndType(post, "DISLIKE");

        model.addAttribute("post", post);
        model.addAttribute("comments", post.getComments());
        model.addAttribute("likeCount", likeCount);
        model.addAttribute("dislikeCount", dislikeCount);
        model.addAttribute("error", error);

        return "view_post";
    }

    @PostMapping("/posts/{id}/react")
    public String reactToPost(@PathVariable Long id,
                              @RequestParam String type,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {

        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Post post = postService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid post Id:" + id));

        Optional<Reaction> existingReaction = reactionService.findByUserAndPost(user, post);
        if (existingReaction.isPresent()) {
            Reaction reaction = existingReaction.get();
            reaction.setType(type);
            reactionService.save(reaction);
        } else {
            Reaction reaction = Reaction.builder()
                    .user(user)
                    .post(post)
                    .type(type)
                    .build();
            reactionService.save(reaction);
        }

        return "redirect:/posts/" + id;
    }

    @GetMapping("/posts/{id}/edit")
    public String showEditPostForm(@PathVariable Long id, Model model,
                                   Authentication authentication, RedirectAttributes redirectAttributes) {

        Post post = postService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid post Id:" + id));

        String email = authentication.getName();
        if (!post.getAuthor().getEmail().equals(email)) {
            redirectAttributes.addFlashAttribute("error", "You are not authorized to edit this post.");
            return "redirect:/posts/" + id;
        }

        model.addAttribute("post", post);
        return "edit_post";
    }

    @PostMapping("/posts/{id}/edit")
    public String editPost(@PathVariable Long id,
                           @ModelAttribute Post updatedPost,
                           @RequestParam("imageFile") MultipartFile imageFile,
                           Authentication authentication,
                           RedirectAttributes redirectAttributes) {

        Post existingPost = postService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid post Id:" + id));

        String email = authentication.getName();
        if (!existingPost.getAuthor().getEmail().equals(email)) {
            redirectAttributes.addFlashAttribute("error", "You are not authorized to edit this post.");
            return "redirect:/posts/" + id;
        }

        existingPost.setTitle(updatedPost.getTitle());
        existingPost.setContent(updatedPost.getContent());

        if (imageFile != null && !imageFile.isEmpty()) {
            try {
                File uploadFolder = new File(uploadDir);
                if (!uploadFolder.exists()) {
                    uploadFolder.mkdirs();
                }

                String originalFilename = imageFile.getOriginalFilename();
                String extension = "";
                if (originalFilename != null && originalFilename.contains(".")) {
                    extension = originalFilename.substring(originalFilename.lastIndexOf('.'));
                }
                String uniqueFilename = UUID.randomUUID().toString() + extension;

                File destinationFile = Paths.get(uploadDir, uniqueFilename).toFile();
                imageFile.transferTo(destinationFile);

                if (existingPost.getImagePath() != null) {
                    String oldImageName = existingPost.getImagePath().replace("/uploads/", "");
                    File oldImage = new File(uploadDir, oldImageName);
                    if (oldImage.exists()) {
                        oldImage.delete();
                    }
                }

                existingPost.setImagePath("/uploads/" + uniqueFilename);

            } catch (IOException e) {
                e.printStackTrace();
                redirectAttributes.addFlashAttribute("error", "Failed to upload new image.");
                return "redirect:/posts/" + id + "/edit";
            }
        }

        try {
            postService.save(existingPost);
            redirectAttributes.addFlashAttribute("success", "Post updated successfully!");
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("error", "An error occurred while updating the post.");
        }

        return "redirect:/posts/" + id;
    }
}
