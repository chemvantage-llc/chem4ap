# My React SPA

This is a simple single-page application built with React and TypeScript. The application consists of a splash page displaying a logo and a question prompt component.

## Project Structure

```
my-react-spa
├── public
│   ├── index.html        # Main HTML file
│   └── logo.png          # Logo image for the splash page
├── src
│   ├── components
│   │   ├── SplashPage.tsx        # Component displaying the logo
│   │   └── QuestionPrompt.tsx     # Component displaying the question prompt
│   ├── App.tsx                   # Main application component
│   ├── index.tsx                 # Entry point of the React application
│   └── types
│       └── index.ts              # Type definitions
├── package.json                   # npm configuration file
├── tsconfig.json                  # TypeScript configuration file
└── README.md                      # Project documentation
```

## Installation

1. Clone the repository:
   ```
   git clone <repository-url>
   ```
2. Navigate to the project directory:
   ```
   cd my-react-spa
   ```
3. Install the dependencies:
   ```
   npm install
   ```

## Usage

To start the application, run:
```
npm start
```
This will launch the application in your default web browser.

## Components

- **SplashPage**: Displays a centered logo image.
- **QuestionPrompt**: Displays a question prompt passed as a prop.

## License

This project is licensed under the MIT License.