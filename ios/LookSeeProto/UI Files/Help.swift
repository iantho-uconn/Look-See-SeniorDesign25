//
//  Help.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/28/26.
//

import SwiftUI

struct FAQItem: Identifiable {
    let id = UUID()
    let category: FAQCategory
    let question: String
    let answer: String
}

enum FAQCategory: String, CaseIterable {
    case all = "All"
    case general = "General"
    case scanning = "Scanning"
    case recording = "Recording"
    case media = "Media & Processing"
    case account = "Account & Promos"
}

struct Help: View {
    @Environment(\.dismiss) var dismiss
    
    @State private var searchText = ""
    @State private var selectedCategory: FAQCategory = .all
    
    // Complete FAQ Data
    let faqs = [
        // Scanning & Map
        FAQItem(category: .scanning, question: "How do I scan a landmark?", answer: "Open LookSee and it will download the model for your current location and bring you to the scan screen. For the functionality to work, you will need to allow LookSee to obtain location data and get the camera feed from your phone. Simply point the camera at the landmark and if it is detected, you will see a green box around it. Click on the box to get a pop-up with more information on the landmark and potentially any promotional messages."),
        FAQItem(category: .scanning, question: "What is the map function?", answer: "The map shows all landmarks in the LookSee model. You can pan and zoom on the map to see other landmarks in the area. The map also shows the title and description information for the landmark, but it does not show any active promotion associated with the landmark. You need to scan the landmark to see the promotions."),
        
        // Recording & Data Entry
        FAQItem(category: .recording, question: "How do I record a landmark?", answer: "To record a landmark, first create a user account or log in and ensure you have a token available for landmark uploads. The free 14-day trial includes two tokens to upload a landmark. You can also purchase tokens if you need more.\n\nOnce logged in, on the scan page, click on the record icon at the bottom center of the screen to bring you to the screen to record a landmark. Then follow the screen prompts to record video(s) of the object, negative detection video of the area around the landmark, and enter the title and description of the landmark. Then upload the videos and data to LookSee."),
        FAQItem(category: .recording, question: "What are some tips for taking video of landmarks?", answer: "You can take multiple shorter videos and LookSee will stitch them together for the submission. Take videos up close and farther away from the object from all directions where a user could scan the landmark. Try to keep the video as steady as possible. Avoid walking backwards while taking videos—you could trip!"),
        FAQItem(category: .recording, question: "Is there an easy way to enter a description for the landmark?", answer: "LookSee can grab description information from existing signage for the landmark by tapping the blue button inside the short description text box. Then the camera feed will be analyzed for text and brackets will be applied around the text. Click the bracket to add the text to the description box.\n\nWhen only one paragraph is put in brackets, you can adjust the distance from the sign to see if you can capture it all. Or you can follow the same process to capture each paragraph. The image to text function will just add the new paragraphs in the description and will not delete the existing data."),
        
        // Media Processing & AI Training
        FAQItem(category: .media, question: "What happens once I upload landmark data?", answer: "Data will be sent to our backend processing to extract images of the landmark and background details that will then be used to train our detection model. If you have no internet service, the data will be placed in queue to be uploaded once you have an internet connection.\n\nOnce we extract the images from the video, we will determine if we have enough unique views of the object to support effective training. If not, we will prompt you to load more video footage on our “Manage My Landmarks” page. If there is enough data, we will train our model to recognize the landmark and then activate the landmark and add it to our map of landmarks."),
        FAQItem(category: .media, question: "What happens if a landmark does not have enough frames to train?", answer: "If needed, LookSee will prompt you for more video data on the “Manage My Landmarks” page about 5-10 minutes after you upload the landmark videos. Loading more video footage will not cost any additional tokens if you upload the video using the “details” link on the landmark on that page.\n\nWhen adding video, make sure to cover every possible perspective where a user could scan the object. Also make sure to take video up close to the target and as far away as feasible. We target 2000 unique frames for training, especially complex objects like large outdoor objects where scanning could occur from all four sides. For simple objects like a picture on a wall, the number of acceptable frames could be as low as 800 frames but the more you can get, the better the detections will work. We recommend uploading more videos, if prompted, at least once to see if the number of usable frames increases before forcing training with a less than optimal frame count."),
        
        // Account, Promotions & Deletion
        FAQItem(category: .account, question: "How do promotions work?", answer: "Any account with existing landmarks and a valid subscription can add promotions to the pop-up when the landmark detection is clicked. The promotions can be changed anytime and can include web pages. You can select multiple landmarks to add the same promotion. Use the promotion to advertise a sale, provide a discount coupon, or give the user additional timely information about the landmark."),
        FAQItem(category: .account, question: "Can I delete a landmark?", answer: "Yes. Request deletion from the “Manage My Landmarks” page. Once the deletion request has been submitted, the landmark will be taken off the active list and no longer appear on the maps page and will not register during scans. The landmark will not be immediately removed from our model. It will only be deleted once additional training occurs for that model. If immediate deletion is required (inappropriate content, etc.), please submit a request by emailing support."),
        FAQItem(category: .general, question: "Is my location data shared?", answer: "Your location is only used to place landmarks on the map and is never shared with third parties without your consent.")
    ]
    
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)
    
    var filteredFAQs: [FAQItem] {
        var results = faqs
        
        // Filter by Category
        if selectedCategory != .all {
            results = results.filter { $0.category == selectedCategory }
        }
        
        // Filter by Search Text
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !query.isEmpty {
            results = results.filter {
                $0.question.lowercased().contains(query) || $0.answer.lowercased().contains(query)
            }
        }
        
        return results
    }

    var body: some View {
        VStack(spacing: 0) {
            
            // Search & Filter Header
            VStack(spacing: 16) {
                // Search Bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField("Search help topics...", text: $searchText)
                        .font(.system(size: 16))
                    
                    if !searchText.isEmpty {
                        Button(action: { searchText = "" }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding(12)
                .background(Color(uiColor: .tertiarySystemFill))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(.horizontal)
                
                // Category Pills
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(FAQCategory.allCases, id: \.self) { category in
                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                                    selectedCategory = category
                                }
                            } label: {
                                Text(category.rawValue)
                                    .font(.system(size: 14, weight: .bold, design: .rounded))
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                                    .background(selectedCategory == category ? primaryColor : Color(uiColor: .tertiarySystemFill))
                                    .foregroundColor(selectedCategory == category ? .white : .primary)
                                    .clipShape(Capsule())
                            }
                        }
                    }
                    .padding(.horizontal)
                }
            }
            .padding(.vertical, 16)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .shadow(color: .black.opacity(0.03), radius: 4, x: 0, y: 2)
            
            // Scrollable FAQ Content
            ScrollView {
                LazyVStack(spacing: 16) {
                    if filteredFAQs.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 40))
                                .foregroundColor(.secondary)
                            Text("No matching answers found.")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(.primary)
                            Text("Try adjusting your search or category.")
                                .font(.system(size: 14))
                                .foregroundColor(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 60)
                    } else {
                        ForEach(filteredFAQs) { faq in
                            FAQCard(faq: faq, primaryColor: primaryColor)
                        }
                    }
                }
                .padding(16)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            
            // Bottom Support & Footer Bar (Pinned)
            VStack(spacing: 16) {
                HStack(spacing: 12) {
                    SupportContactButton(
                        title: "Email Support",
                        systemImage: "envelope.fill",
                        color: primaryColor,
                        action: {
                            if let url = URL(string: "mailto:looksee.support@informationoutpost.com") {
                                UIApplication.shared.open(url)
                            }
                        }
                    )
                    
                    SupportContactButton(
                        title: "Website",
                        systemImage: "globe",
                        color: primaryColor,
                        action: {
                            if let url = URL(string: "https://www.informationoutpost.com") {
                                UIApplication.shared.open(url)
                            }
                        }
                    )
                }
                .padding(.horizontal)
                
                Text("Version \(appVersion) (Build \(appBuild)) • © 2026 Information Outpost")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(.secondary)
            }
            .padding(.top, 16)
            .padding(.bottom, 24)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .shadow(color: .black.opacity(0.05), radius: 8, x: 0, y: -4)
            .ignoresSafeArea(edges: .bottom)
        }
        .navigationTitle("Help & Support")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.4"
    }

    private var appBuild: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "256"
    }
}

// MARK: - Subcomponents

struct FAQCard: View {
    let faq: FAQItem
    let primaryColor: Color
    @State private var isExpanded = false

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                isExpanded.toggle()
            }
        } label: {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 16) {
                    Text(faq.question)
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 2)

                    ZStack {
                        Circle()
                            .fill(isExpanded ? primaryColor.opacity(0.15) : Color(uiColor: .tertiarySystemFill))
                            .frame(width: 28, height: 28)
                        
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(isExpanded ? primaryColor : Color(uiColor: .tertiaryLabel))
                            .rotationEffect(.degrees(isExpanded ? 90 : 0))
                    }
                }

                if isExpanded {
                    Text(faq.answer)
                        .font(.system(size: 14, weight: .regular))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                        .padding(.top, 4)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .padding(16)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: .black.opacity(0.04), radius: 8, x: 0, y: 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct SupportContactButton: View {
    let title: String
    let systemImage: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            action()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.system(size: 16))
                Text(title)
                    .font(.system(size: 15, weight: .bold))
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(color)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    NavigationStack {
        Help()
    }
}
